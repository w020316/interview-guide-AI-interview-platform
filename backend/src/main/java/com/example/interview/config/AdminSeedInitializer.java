package com.example.interview.config;

import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 种子管理员初始化（v1.31.4）
 *
 * <p>启动时读取配置 {@code app.seed-admin.username/password}：若该用户名尚不存在则自动创建账号
 * （BCrypt 加密，幂等——已存在则跳过），使配置的管理员账号开箱即用（如"小吴同学"）。
 *
 * <p>安全说明：密码明文仅用于首次初始化；创建后建议在个人中心修改。若密码配置为空则完全不创建。
 */
@Component
public class AdminSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed-admin.username:}")
    private String seedUsername;

    @Value("${app.seed-admin.password:}")
    private String seedPassword;

    public AdminSeedInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedUsername == null || seedUsername.isBlank() || seedPassword == null || seedPassword.isBlank()) {
            log.debug("未配置种子管理员（app.seed-admin.username/password 为空），跳过初始化");
            return;
        }
        String username = seedUsername.trim();
        if (userRepository.existsByUsername(username)) {
            log.info("种子管理员账号已存在，跳过创建：{}", username);
            return;
        }
        UserEntity user = UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(seedPassword))
                .build();
        try {
            userRepository.saveAndFlush(user);
            log.info("已自动创建种子管理员账号：{}（建议登录后尽快修改初始密码）", username);
        } catch (Exception e) {
            log.warn("种子管理员账号创建失败（可能并发重复注册），可忽略：{}", e.getMessage());
        }
    }
}