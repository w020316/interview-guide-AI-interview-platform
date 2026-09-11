package com.example.interview.config;

import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 种子管理员初始化单元测试（v1.31.4）
 * - 未配置密码：不创建
 * - 账号已存在：跳过
 * - 不存在：创建（BCrypt 加密）
 */
class AdminSeedInitializerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminSeedInitializer newInitializer(String username, String password) {
        AdminSeedInitializer init = new AdminSeedInitializer(userRepository, encoder);
        ReflectionTestUtils.setField(init, "seedUsername", username);
        ReflectionTestUtils.setField(init, "seedPassword", password);
        return init;
    }

    @Test
    @DisplayName("密码为空则不创建")
    void blankPassword_skips() {
        newInitializer("小吴同学", "").run(null);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("账号已存在则跳过创建")
    void existingUser_skips() {
        when(userRepository.existsByUsername("小吴同学")).thenReturn(true);
        newInitializer("小吴同学", "xwtx").run(null);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("账号不存在则创建且密码 BCrypt 加密")
    void missingUser_creates() {
        when(userRepository.existsByUsername("小吴同学")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        newInitializer("小吴同学", "xwtx").run(null);
        verify(userRepository).saveAndFlush(any(UserEntity.class));
        var captor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        UserEntity created = captor.getValue();
        assertTrue(encoder.matches("xwtx", created.getPasswordHash()));
        assertFalse(created.getPasswordHash().equals("xwtx"));
    }
}