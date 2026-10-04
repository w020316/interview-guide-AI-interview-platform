package com.example.interview.ai;

import com.example.interview.common.BusinessException;
import com.example.interview.service.UserAiKeyService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户路由的 ChatModel（第四批 · 竞品清单 #15 用户自持 AI Key）。
 *
 * <p>当前用户配置了自持 Key 时，AI 调用走其自有的 OpenAI 兼容端点（消耗其自身额度）；
 * 否则回落到平台的降级链。**自持 Key 的调用失败不回落平台模型**——那会静默消耗平台额度、
 * 掩盖 Key 配置问题；改为抛出带明确文案的业务异常（全局映射 503，提示用户检查/清除配置）。
 *
 * <p><b>安全性</b>：本类是唯一解密用户 Key 的 AI 调用点；路由判定与客户端构建的**任何异常
 * 都回落平台模型** —— 本包装层绝不能成为 AI 功能的故障点。定时任务等无认证上下文的调用
 * 一律走平台链。功能默认关闭（不配置自持 Key 时行为与 1.51.x 完全一致）。
 *
 * <p>集成点：AiConfig 把平台 FallbackChatModel 包在本类里作为 @Primary ChatModel，
 * 下游的 ChatClient（Agent/求职信/故事库/定制简历）与 JobClassifyService 自动生效。
 * 仅覆盖 call(Prompt)——后端没有流式 AI 调用（已核实），stream() 委托平台模型。
 */
public class SelfKeyAwareChatModel implements ChatModel {

    private final ChatModel platformModel;
    private final UserAiKeyService userAiKeyService;
    /** 每用户（+配置指纹）一个 OpenAI 兼容客户端；变更配置即换新实例，量级 = 用户数 */
    private final Map<String, ChatModel> userModels = new ConcurrentHashMap<>();

    public SelfKeyAwareChatModel(ChatModel platformModel, UserAiKeyService userAiKeyService) {
        this.platformModel = platformModel;
        this.userAiKeyService = userAiKeyService;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatModel user = userChatModelOrNull();
        if (user == null) {
            return platformModel.call(prompt);
        }
        try {
            return user.call(prompt);
        } catch (Exception e) {
            // 自持 Key 失败**不回落平台链**（会静默消耗平台额度、掩盖配置问题），
            // 转为可读的业务异常（503）：用户能明确知道是自己的 Key 出了问题
            String msg = String.valueOf(e.getMessage() == null ? e : e.getMessage());
            throw new BusinessException(
                    "自持 AI Key 调用失败，请检查 Key/端点/模型配置，或在「个人中心 → AI 设置」清除后重试："
                            + msg.substring(0, Math.min(160, msg.length())), e);
        }
    }

    /** 解析当前用户的自持配置并返回其 ChatModel；未配置/无认证上下文/路由异常 → null（走平台链） */
    private ChatModel userChatModelOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            String userId = String.valueOf(auth.getPrincipal());
            if (userId == null || userId.isBlank() || "anonymousUser".equals(userId)) {
                return null;
            }
            return userAiKeyService.settingOf(userId)
                    .map(s -> userModels.computeIfAbsent(
                            userId + "|" + s.baseUrl() + "|" + s.model() + "|" + s.apiKey(),
                            k -> buildUserModel(s)))
                    .orElse(null);
        } catch (Exception e) {
            // 路由判定本身的异常绝不外抛：回落平台链
            return null;
        }
    }

    /** 用用户的自持配置构建 OpenAI 兼容客户端（与 AiConfig 的构造保持一致） */
    private static ChatModel buildUserModel(UserAiKeyService.Setting s) {
        var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(s.baseUrl())
                .apiKey(s.apiKey())
                .build();
        return org.springframework.ai.openai.OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(s.model())
                        .temperature(0.7)
                        .build())
                .build();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 后端没有流式 AI 调用（已核实全部为 .call()）；自持 Key 的流式路由留待有流式需求时再接
        return platformModel.stream(prompt);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return platformModel.getDefaultOptions();
    }

    /** 透传内部模型的描述（既有测试断言 toString 含降级链节点名，包装层不能破坏该口径） */
    @Override
    public String toString() {
        return "SelfKeyAwareChatModel{" + platformModel + "}";
    }
}
