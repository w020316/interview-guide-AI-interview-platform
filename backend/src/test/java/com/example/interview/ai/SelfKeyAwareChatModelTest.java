package com.example.interview.ai;

import com.example.interview.common.BusinessException;
import com.example.interview.service.UserAiKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按用户路由的 ChatModel 测试。
 *
 * <p>两条铁律：① 未配置自持 Key（或无认证上下文）→ 平台模型，行为与 1.51.x 完全一致；
 * ② 自持 Key 的调用失败**不回落平台链**（会静默消耗平台额度），必须转为可读的业务异常。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SelfKeyAwareChatModel 按用户路由")
class SelfKeyAwareChatModelTest {

    @Mock
    private ChatModel platformModel;

    @Mock
    private UserAiKeyService userAiKeyService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u1", null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未配置自持 Key → 走平台模型（功能默认关闭，行为与 1.51.x 一致）")
    void call_withoutSelfKey_usesPlatform() {
        when(userAiKeyService.settingOf("u1")).thenReturn(Optional.empty());
        var model = new SelfKeyAwareChatModel(platformModel, userAiKeyService);

        model.call(new Prompt("hi"));

        verify(platformModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("无认证上下文（定时任务等）→ 走平台模型，且不触达自持 Key 逻辑")
    void call_withoutAuth_usesPlatform() {
        SecurityContextHolder.clearContext();
        var model = new SelfKeyAwareChatModel(platformModel, userAiKeyService);

        model.call(new Prompt("hi"));

        verify(platformModel).call(any(Prompt.class));
        verifyNoInteractions(userAiKeyService);
    }

    @Test
    @DisplayName("自持 Key 配置后 → 走用户端点；调用失败 → 业务异常且不回落平台链")
    void call_withSelfKey_failureDoesNotFallBack() {
        // .invalid 是保留 TLD，DNS 必然解析失败 → 快速失败，不依赖网络
        when(userAiKeyService.settingOf("u1")).thenReturn(Optional.of(
                new UserAiKeyService.Setting("sk-xxxxxxxxxxxxxxxxxxxx",
                        "https://self-key.invalid", "gpt-x")));
        var model = new SelfKeyAwareChatModel(platformModel, userAiKeyService);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自持 AI Key 调用失败");
        verify(platformModel, never()).call(any(Prompt.class));
    }
}
