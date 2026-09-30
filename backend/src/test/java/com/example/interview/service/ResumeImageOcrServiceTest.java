package com.example.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeImageOcrService} 单元测试（v1.45.0）
 *
 * <p>这条链路的意义：手机上没有可靠的「从别的 App 取简历文件」方案，
 * 而截图在任何 App 里都做得到 —— 所以「截图 → 识别 → 分析」是手机端最通用的出路。
 * 测试重点放在**失败必须可见**上：未配置、非图片、类型伪装、上游失败、空正文、
 * 输出被截断，每一种都要给出可区分的原因，不能笼统地「识别失败」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeImageOcrServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private ResumeImageOcrService service;

    /** 合法 PNG 文件头（魔数 + 补齐到 12 字节，够 resolveExtension 比对） */
    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    @BeforeEach
    void setUp() {
        service = new ResumeImageOcrService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "baseUrl", "https://open.bigmodel.cn/api/paas/v4");
        ReflectionTestUtils.setField(service, "apiKey", "test-zhipu-key");
        ReflectionTestUtils.setField(service, "model", "glm-4v-flash");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "resume.png", "image/png", PNG_HEADER);
    }

    private void stubUpstream(String json) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(json, HttpStatus.OK));
    }

    @Test
    @DisplayName("正常识别：返回文本，且把图片以 data URL 形式发给上游")
    void extractText_success() {
        stubUpstream("""
                {"choices":[{"message":{"content":"陈嘉明\\n教育背景：同济大学"},"finish_reason":"stop"}]}
                """);

        var result = service.extractText(pngFile());

        assertThat(result.text()).contains("陈嘉明").contains("同济大学");
        assertThat(result.truncated()).isFalse();
        // 上游必须收到 data URL，而不是裸 base64
        var captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = String.valueOf(captor.getValue().getBody());
        assertThat(body).contains("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_HEADER));
    }

    @Test
    @DisplayName("输出被上游上限截断时如实标记 truncated（长简历不能假装完整）")
    void extractText_truncatedFlag() {
        stubUpstream("""
                {"choices":[{"message":{"content":"陈嘉明\\n工作经历：..."},"finish_reason":"length"}]}
                """);

        var result = service.extractText(pngFile());

        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).isNotBlank();
    }

    @Test
    @DisplayName("未配置 api-key 时明确说「未配置」，而不是「识别失败」")
    void notConfigured_clearMessage() {
        ReflectionTestUtils.setField(service, "apiKey", "");

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.extractText(pngFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    @DisplayName("非图片内容被拒绝（按文件头判定，不信任文件名）")
    void nonImage_rejected() {
        var fake = new MockMultipartFile("file", "resume.png", "image/png", "%PDF-1.4 fake".getBytes());

        assertThatThrownBy(() -> service.extractText(fake))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是受支持的图片格式");
    }

    @Test
    @DisplayName("声明类型与文件头不一致时拒绝（防伪装文件）")
    void declaredTypeMismatch_rejected() {
        // 内容是 PNG 魔数，却声明成 jpeg
        var spoofed = new MockMultipartFile("file", "x.jpg", "image/jpeg", PNG_HEADER);

        assertThatThrownBy(() -> service.extractText(spoofed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("缺 Content-Type 的手机上传仍可识别（以文件头为准）")
    void missingContentType_stillWorks() {
        stubUpstream("""
                {"choices":[{"message":{"content":"张三"},"finish_reason":"stop"}]}
                """);
        var noCt = new MockMultipartFile("file", "shot.png", null, PNG_HEADER);

        assertThat(service.extractText(noCt).text()).isEqualTo("张三");
    }

    @Test
    @DisplayName("上游返回空正文时报错并带上 finish_reason，不返回空串假装成功")
    void emptyContent_throws() {
        stubUpstream("""
                {"choices":[{"message":{"content":""},"finish_reason":"length"}]}
                """);

        assertThatThrownBy(() -> service.extractText(pngFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空内容")
                .hasMessageContaining("length");
    }

    @Test
    @DisplayName("超大图片在发请求前就被拒绝（不浪费一次上游调用）")
    void oversizedImage_rejected() {
        byte[] big = new byte[7 * 1024 * 1024];
        System.arraycopy(PNG_HEADER, 0, big, 0, PNG_HEADER.length);
        var huge = new MockMultipartFile("file", "big.png", "image/png", big);

        assertThatThrownBy(() -> service.extractText(huge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("过大");
    }
}
