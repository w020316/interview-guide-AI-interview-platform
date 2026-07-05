package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证接口（无需 token 即可访问）
 * POST /api/auth/register  — 注册
 * POST /api/auth/login     — 登录，返回 JWT
 * POST /api/auth/logout    — 登出（前端清 token，服务端无状态）
 */
@Tag(name = "认证", description = "用户注册与登录")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /** 登录失败计数：IP → 失败次数 */
    private final ConcurrentHashMap<String, LoginFailInfo> loginFailMap = new ConcurrentHashMap<>();

    /** 最大失败次数（超过则锁定） */
    private static final int MAX_FAIL_COUNT = 5;

    /** 锁定时长（5 分钟） */
    private static final long LOCK_DURATION_MS = 5 * 60 * 1000L;

    /** 登录失败信息 */
    private static class LoginFailInfo {
        AtomicInteger count = new AtomicInteger(0);
        volatile long lastFailTime = 0;
    }

    /** 定期清理过期的登录失败计数，防止内存泄漏 */
    private void cleanupExpiredLoginFails() {
        long now = System.currentTimeMillis();
        loginFailMap.entrySet().removeIf(entry -> {
            LoginFailInfo info = entry.getValue();
            // 锁定过期或 30 分钟无失败则清理
            return info.count.get() >= MAX_FAIL_COUNT
                    ? (now - info.lastFailTime) > LOCK_DURATION_MS
                    : (now - info.lastFailTime) > 30 * 60 * 1000L;
        });
    }

    /**
     * 注册
     * Body: {"username":"alice","password":"123456","email":"a@b.com"}
     * 安全加固：用户名长度 3-32，密码长度 6-64，邮箱格式校验
     */
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<String> register(@RequestBody Map<String, String> req) {
        String username = req.get("username");
        String password = req.get("password");
        String email    = req.get("email");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }
        // 用户名长度与字符校验（支持中文、字母、数字、下划线，2-32 字符）
        if (username.length() < 2 || username.length() > 32) {
            return Result.error(400, "用户名长度需 2-32 字符");
        }
        if (!username.matches("^[A-Za-z0-9_\\u4e00-\\u9fa5]+$")) {
            return Result.error(400, "用户名只能包含中文、字母、数字和下划线");
        }
        // 密码强度校验（6-64 字符）
        if (password.length() < 6 || password.length() > 64) {
            return Result.error(400, "密码长度需 6-64 字符");
        }
        // 邮箱格式校验
        if (email != null && !email.isBlank()) {
            if (email.length() > 128 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                return Result.error(400, "邮箱格式不正确");
            }
        }
        if (userRepository.existsByUsername(username)) {
            return Result.error(400, "用户名已存在");
        }
        if (email != null && !email.isBlank() && userRepository.existsByEmail(email)) {
            return Result.error(400, "邮箱已被注册");
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(email == null || email.isBlank() ? null : email)
                .build();
        userRepository.save(user);

        return Result.success(jwtUtil.generateToken(username));
    }

    /**
     * 登录
     * Body: {"username":"alice","password":"123456"}
     * 安全加固：IP 维度登录失败 5 次后锁定 5 分钟
     */
    @Operation(summary = "用户登录，返回 JWT token")
    @PostMapping("/login")
    public Result<String> login(@RequestBody Map<String, String> req, HttpServletRequest request) {
        String username = req.get("username");
        String password = req.get("password");

        if (username == null || password == null) {
            return Result.error(400, "用户名和密码不能为空");
        }
        // 登录接口也校验长度上限，防止超长字符串 DoS
        if (username.length() > 64 || password.length() > 128) {
            return Result.error(400, "用户名或密码长度超限");
        }

        // 顺便清理过期的失败计数
        cleanupExpiredLoginFails();

        String clientIp = resolveClientIp(request);

        // 检查是否被锁定
        LoginFailInfo failInfo = loginFailMap.get(clientIp);
        if (failInfo != null && failInfo.count.get() >= MAX_FAIL_COUNT) {
            long elapsed = System.currentTimeMillis() - failInfo.lastFailTime;
            if (elapsed < LOCK_DURATION_MS) {
                long remainingMin = (LOCK_DURATION_MS - elapsed) / 60000 + 1;
                return Result.error(429, "登录失败次数过多，请 " + remainingMin + " 分钟后再试");
            } else {
                // 锁定过期，重置计数
                loginFailMap.remove(clientIp);
            }
        }

        UserEntity user = userRepository.findByUsername(username)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 记录失败次数
            LoginFailInfo info = loginFailMap.computeIfAbsent(clientIp, k -> new LoginFailInfo());
            int failCount = info.count.incrementAndGet();
            info.lastFailTime = System.currentTimeMillis();

            int remaining = MAX_FAIL_COUNT - failCount;
            if (remaining > 0) {
                return Result.error(401, "用户名或密码错误，还可尝试 " + remaining + " 次");
            } else {
                return Result.error(429, "登录失败次数过多，请 5 分钟后再试");
            }
        }

        // 登录成功，清除失败计数
        loginFailMap.remove(clientIp);

        return Result.success(jwtUtil.generateToken(username));
    }

    /**
     * 登出
     * 当前 JWT 为无状态方案，登出仅由前端清除 token 即可。
     * 此接口保留供未来扩展 token 黑名单使用，当前返回成功。
     */
    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success(null);
    }

    /**
     * 解析客户端 IP（仅信任内网代理）
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remoteAddr)) {
            return forwarded.split(",")[0].trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.equals("127.0.0.1")
                || ip.startsWith("::1")) {
            return true;
        }
        // 精确校验 172.16.0.0/12（172.16.0.0 - 172.31.255.255），避免 172.* 误判
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
