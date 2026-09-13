package com.example.interview.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonBodySizeLimitFilter} 单元测试（P2-11：JSON 请求体大小限制）
 */
class JsonBodySizeLimitFilterTest {

    private final JsonBodySizeLimitFilter filter = new JsonBodySizeLimitFilter();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "maxJsonBodyBytes", 1024L);
    }

    @Test
    @DisplayName("JSON 请求体超过上限返回 413，不进入后续链")
    void overLimit_returns413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContentType("application/json");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("413");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("JSON 请求体在上限内正常放行")
    void withinLimit_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContentType("application/json");
        request.setContent("{\"username\":\"a\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("非 JSON 请求（如 multipart）不受限制")
    void nonJson_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/resume/upload");
        request.setContentType("multipart/form-data; boundary=xxx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("上限配置为 0 时禁用检查")
    void disabled_zeroLimit_passesThrough() throws Exception {
        ReflectionTestUtils.setField(filter, "maxJsonBodyBytes", 0L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        request.setContentType("application/json");
        request.setContent(new byte[9999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
