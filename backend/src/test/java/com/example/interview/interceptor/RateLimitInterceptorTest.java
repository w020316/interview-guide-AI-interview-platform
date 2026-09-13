package com.example.interview.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RateLimitInterceptor 单元测试")
class RateLimitInterceptorTest {

    private static RateLimitInterceptor interceptorWithLimit(int perMinute) {
        RateLimitInterceptor interceptor = new RateLimitInterceptor();
        // P2-10：阈值可配（app.rate-limit.per-minute，默认 60）；单测注入小值验证限流行为
        ReflectionTestUtils.setField(interceptor, "perMinuteLimit", perMinute);
        return interceptor;
    }

    @Test
    @DisplayName("配额内请求应通过")
    void requestsWithinLimit_shouldPass() throws Exception {
        RateLimitInterceptor interceptor = interceptorWithLimit(10);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 10; i++) {
            boolean result = interceptor.preHandle(request, response, new Object());
            assertThat(result).as("第 %d 次请求应通过", i + 1).isTrue();
        }
    }

    @Test
    @DisplayName("超出配额的请求应被限流（429）")
    void overLimit_shouldBeRateLimited() throws Exception {
        RateLimitInterceptor interceptor = interceptorWithLimit(10);
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
