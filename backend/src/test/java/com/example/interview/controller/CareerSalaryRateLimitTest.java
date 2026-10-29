package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.career.CareerProfileService;
import com.example.interview.service.career.SalaryCoachService;
import com.example.interview.util.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 谈薪助手的控制器级限流口径（30 次/分钟/用户）。
 *
 * <p>为什么这里还要单独锁一次：{@code AiRateLimitFilter} 是按路径的全局配额，
 * 控制器里的 {@code salaryLimiter} 是「单用户反复改写同一个数字就重调模型」的闸门。
 * 两层都必须在位——少了后者，一个人可以在一分钟内把 60 次 AI 配额全烧在同一份建议上。
 *
 * <p>与其它限流用例同一做法：临时换配额 1 的实例，退出时原样放回，避免污染共用上下文的
 * {@link CareerControllerTest}。
 */
@WebMvcTest(controllers = CareerController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("CareerController 谈薪限流 429 口径")
class CareerSalaryRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CareerController careerController;

    @MockBean
    private CareerProfileService careerProfileService;

    @MockBean
    private SalaryCoachService salaryCoachService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private Object originalLimiter;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-1", null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(salaryCoachService.coach(anyString(), any())).thenReturn(Map.of("headline", "ok"));

        originalLimiter = ReflectionTestUtils.getField(careerController, "salaryLimiter");
        ReflectionTestUtils.setField(careerController, "salaryLimiter",
                new PerUserRateLimiter(1, 60_000L));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(careerController, "salaryLimiter", originalLimiter);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("配额耗尽后返回真 HTTP 429，且超限请求不会再次调用 AI")
    void salary_beyondQuota_returnsRealHttp429() throws Exception {
        String body = "{\"mode\":\"OFFER\",\"targetJob\":\"Java 后端\",\"offerMonthly\":22000}";

        mockMvc.perform(post("/api/career/salary").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/career/salary").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("谈薪建议生成太频繁了，请稍后再试"));

        verify(salaryCoachService, times(1)).coach(anyString(), any());
    }
}
