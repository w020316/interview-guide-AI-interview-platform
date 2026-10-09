package com.example.interview.service;

import com.example.interview.common.UserKeyCipher;
import com.example.interview.entity.UserAiSettingEntity;
import com.example.interview.repository.UserAiSettingRepository;
import com.example.interview.util.SsrUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 用户自持 AI Key 的存储 / 校验 / 掩码（第四批 · 竞品清单 #15）。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>密文落库（AES-256-GCM，主密钥由 JWT_SECRET 派生），明文只在解密瞬间存在于内存；</li>
 *   <li>对外接口只回显**掩码**（前 2 + 后 4 位），永不回显完整 Key；</li>
 *   <li>baseUrl 强制 https 且拒绝私网/保留/回环地址 —— 防 SSRF（用户提供的 URL
 *       会被后端发起请求，不做校验等于开放内网探测）。</li>
 * </ul>
 */
@Slf4j
@Service
public class UserAiKeyService {

    /** 自持 Key 的最小长度（sk- 前缀的 OpenAI 兼容 Key 实际都在 40+） */
    public static final int MIN_KEY_LEN = 20;
    private static final int MAX_KEY_LEN = 300;

    private final UserAiSettingRepository repository;
    private final UserKeyCipher cipher;

    @Autowired
    public UserAiKeyService(UserAiSettingRepository repository, UserKeyCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /** 解密后的完整配置；未配置/解密失败返回 empty（解密失败视为配置失效，提示重新设置） */
    public Optional<Setting> settingOf(String userId) {
        return repository.findById(userId).flatMap(e -> {
            try {
                return Optional.of(new Setting(cipher.decrypt(e.getApiKeyCipher()),
                        e.getBaseUrl(), e.getModel()));
            } catch (Exception ex) {
                // 主密钥变更/密文损坏：按「未配置」处理并告警 —— 不能让一条坏记录拖垮 AI 调用
                log.error("用户自持 AI Key 解密失败（已按未配置处理）userId={}", userId);
                return Optional.empty();
            }
        });
    }

    /** 掩码视图（对外接口用，永不回显完整 Key）；未配置返回 configured=false */
    public Optional<View> viewOf(String userId) {
        Optional<UserAiSettingEntity> e = repository.findById(userId);
        if (e.isEmpty()) {
            return Optional.empty();
        }
        UserAiSettingEntity s = e.get();
        String masked;
        try {
            masked = mask(cipher.decrypt(s.getApiKeyCipher()));
        } catch (Exception ex) {
            masked = "（密文损坏，请重新设置）";
        }
        return Optional.of(new View(true, masked, s.getBaseUrl(), s.getModel(),
                s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString()));
    }

    /** 保存（新增或更新）。校验失败抛 IllegalArgumentException → HTTP 400 */
    public void save(String userId, String apiKey, String baseUrl, String model) {
        String err = validate(apiKey, baseUrl, model);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        UserAiSettingEntity e = repository.findById(userId)
                .orElseGet(() -> UserAiSettingEntity.builder().userId(userId).build());
        e.setApiKeyCipher(cipher.encrypt(apiKey.trim()));
        e.setBaseUrl(baseUrl.trim());
        e.setModel(model.trim());
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        log.info("用户自持 AI Key 已更新 userId={} baseUrl={} model={}", userId, baseUrl.trim(), model.trim());
    }

    /** 删除（清除后回到平台 Key 兜底） */
    public void delete(String userId) {
        repository.deleteById(userId);
    }

    /** 保存前校验；返回 null 表示通过 */
    public static String validate(String apiKey, String baseUrl, String model) {
        if (apiKey == null || apiKey.trim().length() < MIN_KEY_LEN) {
            return "apiKey 至少 " + MIN_KEY_LEN + " 个字符";
        }
        if (apiKey.trim().length() > MAX_KEY_LEN) {
            return "apiKey 过长（最多 " + MAX_KEY_LEN + " 个字符）";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return "baseUrl 不能为空";
        }
        String b = baseUrl.trim();
        if (!b.startsWith("https://")) {
            return "baseUrl 必须以 https:// 开头";
        }
        try {
            URI uri = URI.create(b);
            InetAddress addr = InetAddress.getByName(uri.getHost());
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                    || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) {
                return "baseUrl 不允许指向内网/保留地址";
            }
        } catch (Exception e) {
            return "baseUrl 无法解析有效主机";
        }
        // B-06：上面的基础判定弱于 SsrUrlValidator——未拦 CGNAT 100.64.0.0/10、阿里云元数据
        // 100.100.100.200、IPv6 ULA fc00::/7，且 getByName 只解析首个 IP（多 A 记录可绕过）。
        // 复用统一校验器覆盖上述网段并校验全部解析 IP。文案沿用「不允许指向内网/保留地址」，
        // 以兼容既有测试与前端提示。
        if (!SsrUrlValidator.validate(b).ok) {
            return "baseUrl 不允许指向内网/保留地址";
        }
        if (model == null || model.isBlank()) {
            return "model 不能为空";
        }
        return null;
    }

    /** 掩码：前 2 + 后 4 位，中间以 *** 代替；过短一律 *** */
    public static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 2) + "***" + key.substring(key.length() - 4);
    }

    /**
     * 连通性测试：用用户自持配置发一个最小请求（消耗其自身额度，不消耗平台额度）。
     * 无论成功失败都返回结构化结果（不抛异常），由前端直接展示。
     */
    public Map<String, Object> testCall(String userId) {
        Setting s = settingOf(userId)
                .orElseThrow(() -> new IllegalArgumentException("尚未配置自持 AI Key"));
        long t0 = System.currentTimeMillis();
        try {
            var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl(s.baseUrl()).apiKey(s.apiKey()).build();
            var model = org.springframework.ai.openai.OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .model(s.model()).maxTokens(20).build())
                    .build();
            var gen = model.call(new org.springframework.ai.chat.prompt.Prompt("请回复：OK"))
                    .getResult().getOutput();
            String reply = gen.getText() == null ? "" : gen.getText().trim();
            return Map.of("ok", true, "latencyMs", System.currentTimeMillis() - t0,
                    "reply", reply.length() > 60 ? reply.substring(0, 60) : reply);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage() == null ? e : e.getMessage());
            return Map.of("ok", false, "latencyMs", System.currentTimeMillis() - t0,
                    "message", msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    /** 解密后的自持配置（供 AI 路由使用） */
    public record Setting(String apiKey, String baseUrl, String model) {}

    /** 对外掩码视图 */
    public record View(boolean configured, String keyMasked, String baseUrl, String model, String updatedAt) {}
}
