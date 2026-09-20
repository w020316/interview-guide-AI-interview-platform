package com.example.interview.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GlobalExceptionHandler 单元测试")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("IllegalArgumentException → code 400")
    void illegalArgument_returns400() {
        Result<Void> result = handler.handleIllegalArgument(new IllegalArgumentException("bad param"));
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("bad param");
    }

    @Test
    @DisplayName("RuntimeException → code 500")
    void runtimeException_returns500() {
        Result<Void> result = handler.handleRuntime(new RuntimeException("crash"));
        assertThat(result.code()).isEqualTo(500);
    }

    @Test
    @DisplayName("DataAccessException → code 500")
    void dataAccessException_returns500() {
        Result<Void> result = handler.handleDataAccess(
                new DataIntegrityViolationException("db error"));
        assertThat(result.code()).isEqualTo(500);
    }

    // ── 全异常类型分支 ──

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 聚合字段错误消息")
    void validationError_returns400WithFieldMessages() throws Exception {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        var sample = new Object() {
            @jakarta.validation.constraints.NotBlank(message = "用户名不能为空")
            public final String username = "";
        };
        var violations = validator.validate(sample);
        var ex = new org.springframework.web.bind.MethodArgumentNotValidException(
                null, new org.springframework.validation.BeanPropertyBindingResult(sample, "request") {
            {
                // 将违规信息注册进 BindingResult
                for (var v : violations) {
                    addError(new org.springframework.validation.FieldError(
                            "request", v.getPropertyPath().toString(), v.getMessage()));
                }
            }
        });

        Result<Void> result = handler.handleValidation(ex);
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("参数校验失败").contains("用户名不能为空");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException → 400 固定文案")
    void maxUploadSize_returns400() {
        Result<Void> result = handler.handleMaxUploadSize(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024));
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("10MB");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException → 405 携带方法名")
    void methodNotSupported_returns405() {
        Result<Void> result = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"));
        assertThat(result.code()).isEqualTo(405);
        assertThat(result.message()).contains("PATCH");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException → 400 JSON 语法提示")
    void notReadable_returns400() {
        Result<Void> result = handler.handleNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad json",
                        (org.springframework.http.HttpInputMessage) null));
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("请求体格式错误");
    }

    @Test
    @DisplayName("NoHandlerFoundException → 404 携带请求 URL")
    void noHandler_returns404() {
        var ex = new org.springframework.web.servlet.NoHandlerFoundException(
                "GET", "/api/missing", null);
        Result<Void> result = handler.handleNoHandler(ex);
        assertThat(result.code()).isEqualTo(404);
        assertThat(result.message()).contains("/api/missing");
    }

    @Test
    @DisplayName("NoResourceFoundException → 404（线上 500 回归防线，2026-09-20 阶段四实测发现）")
    void noResourceFound_returns404() {
        // Spring Boot 3.2+ 未匹配路径由静态资源处理器抛此异常，此前落到兜底返回 500
        var ex = new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "stats/overview");
        Result<Void> result = handler.handleNoResource(ex);
        assertThat(result.code()).isEqualTo(404);
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400（线上 500 回归防线）")
    void typeMismatch_returns400() {
        // 线上实测：GET /api/jobs/abc、/api/jobs?page=abc 均返回 500，应为 400
        var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, null);
        Result<Void> result = handler.handleTypeMismatch(ex);
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("id");
        // 不回显用户传入的原始值（反射型注入面）
        assertThat(result.message()).doesNotContain("abc");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException → 400（线上 500 回归防线）")
    void missingParam_returns400() {
        // 线上实测：GET /api/knowledge/search（无 query）返回 500，应为 400
        var ex = new org.springframework.web.bind.MissingServletRequestParameterException(
                "query", "String");
        Result<Void> result = handler.handleMissingParam(ex);
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.message()).contains("query");
    }

    @Test
    @DisplayName("AuthenticationException → 401 未认证提示")
    void authException_returns401() {
        Result<Void> result = handler.handleAuth(
                new org.springframework.security.core.AuthenticationException("token 过期") {});
        assertThat(result.code()).isEqualTo(401);
        assertThat(result.message()).contains("未认证");
    }

    @Test
    @DisplayName("BusinessException → 503 message 原样透出")
    void businessException_returns503() {
        Result<Void> result = handler.handleBusiness(
                new BusinessException("AI 服务暂时不可用，请稍后重试"));
        assertThat(result.code()).isEqualTo(503);
        assertThat(result.message()).isEqualTo("AI 服务暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("AccessDeniedException → 403 无权访问提示")
    void accessDenied_returns403() {
        Result<Void> result = handler.handleAccessDenied(
                new org.springframework.security.access.AccessDeniedException("denied"));
        assertThat(result.code()).isEqualTo(403);
        assertThat(result.message()).contains("无权访问");
    }

    @Test
    @DisplayName("TimeoutException → 504 AI 服务超时提示")
    void timeout_returns504() {
        Result<Void> result = handler.handleTimeout(
                new java.util.concurrent.TimeoutException("call timeout"));
        assertThat(result.code()).isEqualTo(504);
        assertThat(result.message()).contains("AI 服务响应超时");
    }

    @Test
    @DisplayName("OptimisticLockingFailureException → 409 冲突提示")
    void optimisticLocking_returns409() {
        Result<Void> result = handler.handleOptimisticLocking(
                new org.springframework.dao.OptimisticLockingFailureException("version conflict"));
        assertThat(result.code()).isEqualTo(409);
        assertThat(result.message()).contains("内容已被更新");
    }

    @Test
    @DisplayName("IllegalStateException → 500 不透出内部细节")
    void illegalState_returns500WithoutDetails() {
        Result<Void> result = handler.handleIllegalState(
                new IllegalStateException("secret internal path /admin/x"));
        assertThat(result.code()).isEqualTo(500);
        assertThat(result.message()).contains("服务器内部错误");
        assertThat(result.message()).doesNotContain("secret");
    }

    @Test
    @DisplayName("Exception 兜底 → 500 服务器内部错误")
    void generalException_returns500() {
        Result<Void> result = handler.handleGeneral(new Exception("unexpected"));
        assertThat(result.code()).isEqualTo(500);
        assertThat(result.message()).isEqualTo("服务器内部错误");
    }
}
