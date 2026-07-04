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
}
