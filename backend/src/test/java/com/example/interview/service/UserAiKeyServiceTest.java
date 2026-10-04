package com.example.interview.service;

import com.example.interview.common.UserKeyCipher;
import com.example.interview.entity.UserAiSettingEntity;
import com.example.interview.repository.UserAiSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户自持 AI Key 服务测试：保存校验（含 SSRF 防护）、掩码、解密视图。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAiKeyService 用户自持 AI Key")
class UserAiKeyServiceTest {

    @Mock
    private UserAiSettingRepository repository;

    private UserAiKeyService service;

    @BeforeEach
    void setUp() {
        // 必须在 Mockito 注入 @Mock 之后构造（字段初始化时 repository 还是 null）
        service = new UserAiKeyService(repository, new UserKeyCipher("0123456789abcdef0123456789abcdef"));
    }

    @Test
    @DisplayName("validate：apiKey 过短/http 端点/内网地址/空 model 均被拒（含 SSRF 防护）")
    void validate_rules() {
        assertThat(UserAiKeyService.validate("short", "https://api.x.com", "gpt-x"))
                .contains("至少 20 个字符");
        assertThat(UserAiKeyService.validate("sk-abcdefghijklmnopqrstuvwxyz", "http://api.x.com", "gpt-x"))
                .contains("必须以 https:// 开头");
        assertThat(UserAiKeyService.validate("sk-abcdefghijklmnopqrstuvwxyz", "https://127.0.0.1:8443", "gpt-x"))
                .contains("不允许指向内网/保留地址");
        assertThat(UserAiKeyService.validate("sk-abcdefghijklmnopqrstuvwxyz", "https://169.254.169.254/", "gpt-x"))
                .contains("不允许指向内网/保留地址");
        assertThat(UserAiKeyService.validate("sk-abcdefghijklmnopqrstuvwxyz", "https://api.x.com", " "))
                .contains("model 不能为空");
        assertThat(UserAiKeyService.validate("sk-abcdefghijklmnopqrstuvwxyz", "https://api.x.com", "gpt-x"))
                .isNull();
    }

    @Test
    @DisplayName("save：合法输入 → 密文可解回原值，不落明文")
    void save_encrypts() {
        when(repository.findById("u1")).thenReturn(Optional.empty());

        service.save("u1", "sk-abcdefghijklmnopqrstuvwxyz", "https://api.x.com", "gpt-x");

        ArgumentCaptor<UserAiSettingEntity> captor =
                ArgumentCaptor.forClass(UserAiSettingEntity.class);
        verify(repository).save(captor.capture());
        UserAiSettingEntity saved = captor.getValue();
        assertThat(saved.getApiKeyCipher()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz");
        assertThat(new UserKeyCipher("0123456789abcdef0123456789abcdef").decrypt(saved.getApiKeyCipher()))
                .isEqualTo("sk-abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    @DisplayName("save：校验失败 → 不落库")
    void save_invalid_neverSaves() {
        assertThatThrownBy(() -> service.save("u1", "short", "https://api.x.com", "gpt-x"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("viewOf：掩码不泄露完整 Key")
    void view_masksKey() {
        when(repository.findById("u1")).thenReturn(Optional.empty());
        service.save("u1", "sk-abcdefghijklmnopqrstuvwxyz", "https://api.x.com", "gpt-x");
        // mock 的 save 不落库：捕获写入的实体后 stub findById，模拟「已保存」状态
        ArgumentCaptor<UserAiSettingEntity> captor = ArgumentCaptor.forClass(UserAiSettingEntity.class);
        verify(repository).save(captor.capture());
        when(repository.findById("u1")).thenReturn(Optional.of(captor.getValue()));

        var view = service.viewOf("u1").orElseThrow();

        assertThat(view.configured()).isTrue();
        assertThat(view.keyMasked()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz");
        assertThat(view.keyMasked()).contains("***");
        assertThat(view.baseUrl()).isEqualTo("https://api.x.com");
        assertThat(view.model()).isEqualTo("gpt-x");
    }

    @Test
    @DisplayName("viewOf：密文损坏 → 按未配置处理并给出可读提示（不抛异常拖垮接口）")
    void view_corruptCipher() {
        when(repository.findById("u1")).thenReturn(Optional.of(
                UserAiSettingEntity.builder().userId("u1")
                        .apiKeyCipher("not-a-valid-cipher").baseUrl("https://api.x.com")
                        .model("gpt-x").build()));

        var view = service.viewOf("u1").orElseThrow();
        assertThat(view.keyMasked()).contains("重新设置");
    }

    @Test
    @DisplayName("delete：清除后回到平台 Key 兜底")
    void delete() {
        service.delete("u1");
        verify(repository).deleteById("u1");
    }
}
