package com.example.interview.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RateLimitInterceptor 单元测试")
class RateLimitInterceptorTest {

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor();

    @Test
    @DisplayName("前 10 次请求应通过")
    void first10Requests_shouldPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 10; i++) {
            boolean result = interceptor.preHandle(request, response, new Object());
            assertThat(result).as("第 %d 次请求应通过", i + 1).isTrue();
        }
    }

    @Test
    @DisplayName("第 11 次请求应被限流（429）")
    void eleventhRequest_shouldBeRateLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.99");  // 独立 IP 不受上个测试影响
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 10; i++) {
            interceptor.preHandle(request, response, new Object());
        }

        boolean result = interceptor.preHandle(request, response, new Object());
        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }
}
