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
 * <p>v1.31.5：账号已存在时重置密码为配置值。此前仅"不存在才创建"，若 Render 上曾部署过
 * 空/旧密码配置，种子密码变更后无法生效，表现为"管理员账号密码不对"无法登录；改为
 * 「配置即事实来源」——每次启动以配置的密码为准，保证种子管理员始终可用。
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
        String encoded = passwordEncoder.encode(seedPassword);
        try {
            if (userRepository.existsByUsername(username)) {
                // v1.31.5：已存在则重置密码为配置值，保证种子管理员密码始终可用
                userRepository.findByUsername(username).ifPresent(user -> {
                    user.setPasswordHash(encoded);
                    userRepository.saveAndFlush(user);
                    log.info("种子管理员账号已存在，已重置密码：{}", username);
                });
                return;
            }
            UserEntity user = UserEntity.builder()
                    .username(username)
                    .passwordHash(encoded)
                    .build();
            userRepository.saveAndFlush(user);
            log.info("已自动创建种子管理员账号：{}（建议登录后尽快修改初始密码）", username);
        } catch (Exception e) {
            log.warn("种子管理员账号初始化失败（可能并发重复注册），可忽略：{}", e.getMessage());
        }
    }
}