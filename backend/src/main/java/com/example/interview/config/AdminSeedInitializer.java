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
 * （BCrypt 加密），使配置的管理员账号开箱即用（如"小吴同学"）。密码配置为空则完全不创建。
 *
 * <p>v1.33.0（P2-02）：「已存在即重置密码」改为显式 opt-in（环境变量 APP_SEED_ADMIN_RESET=true）。
 * 此前 v1.31.5 的「配置即事实来源」每次启动强制重置，导致管理员在个人中心改掉的密码会在
 * 每次自动部署重启后被静默还原为（通常较弱的）环境变量密码。默认仅首次创建；
 * 需要恢复默认密码时显式设置 RESET 标志重启一次即可。
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

    @Value("${app.seed-admin.reset-existing:${APP_SEED_ADMIN_RESET:false}}")
    private boolean resetExisting;

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
                if (!resetExisting) {
                    // P2-02：默认不重置，管理员改过的密码不被部署重启静默还原
                    log.info("种子管理员账号已存在，跳过重置（如需重置密码请设置 APP_SEED_ADMIN_RESET=true 并重启）");
                    return;
                }
                userRepository.findByUsername(username).ifPresent(user -> {
                    user.setPasswordHash(encoded);
                    userRepository.saveAndFlush(user);
                    log.info("APP_SEED_ADMIN_RESET=true：已重置种子管理员密码：{}", username);
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