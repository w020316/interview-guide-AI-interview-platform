package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证接口（无需 token 即可访问）
 * POST /api/auth/register  — 注册
 * POST /api/auth/login     — 登录，返回 JWT
 * POST /api/auth/logout    — 登出（清前端 token，并把 jti 写入服务端黑名单即吊销）
 */
@Tag(name = "认证", description = "用户注册与登录")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /** 登出吊销黑名单（P1 2026-09-20）：required=false 兼容 @WebMvcTest 切片 */
    @Autowired(required = false)
    private com.example.interview.security.TokenBlacklistService tokenBlacklist;

    @Autowired
    private com.example.interview.service.UserBanRegistry userBanRegistry;

    /** 登录失败计数：IP → 失败次数 */
    private final ConcurrentHashMap<String, LoginFailInfo> loginFailMap = new ConcurrentHashMap<>();

    /**
     * 登录失败计数：账号（小写用户名）→ 失败次数（P1-02，2026-09-23）。
     *
     * <p><b>为什么必须补这一维</b>：原实现只按 IP 计数，而 IP 是攻击者可自由轮换的资源
     * ——2026-09-23 生产实测：对同一账号连续输错密码，返回的「还可尝试 N 次」在
     * 4→3→4→3 之间交替（每次请求落到不同边缘节点、被当成不同 IP），
     * 5 次锁定形同虚设，可以无限重试同一个账号。
     * 账号维度不随 IP 变化，才能真正保护账号本身。
     *
     * <p>代价：存在「用已知用户名故意触发锁定」的 DoS 面。因此沿用同样的
     * 5 分钟短锁定窗口，把影响限制在「临时不可登录」而非「永久封禁」。
     */
    private final ConcurrentHashMap<String, LoginFailInfo> accountFailMap = new ConcurrentHashMap<>();

    /**
     * 注册限流（P2-04）：按 IP 默认 5 次/小时滑动窗口，防脚本批量注册垃圾账号与
     * 「用户名/邮箱已存在」回显驱动的批量枚举。
     * 限流拦截器整体豁免 /api/auth/**，注册需在此处单独限流。
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.register-limit-per-hour:5}")
    private int registerLimitPerHour;

    private com.example.interview.util.PerUserRateLimiter registerLimiter;

    @jakarta.annotation.PostConstruct
    void initRegisterLimiter() {
        registerLimiter = new com.example.interview.util.PerUserRateLimiter(registerLimitPerHour, 60 * 60 * 1000L);
    }

    /** 最大失败次数（超过则锁定） */
    private static final int MAX_FAIL_COUNT = 5;

    /** 账号维度最大失败次数（P1-02：不随 IP 轮换而重置，见 accountFailMap 注释） */
    private static final int MAX_FAIL_COUNT_PER_ACCOUNT = 5;

    /** 锁定时长（5 分钟） */
    private static final long LOCK_DURATION_MS = 5 * 60 * 1000L;

    /** 登录失败信息 */
    private static class LoginFailInfo {
        AtomicInteger count = new AtomicInteger(0);
        volatile long lastFailTime = 0;
    }

    /**
     * 判断某个失败计数桶是否处于锁定期；锁定已过期则顺手移除该桶。
     *
     * @return 处于锁定中返回剩余分钟数（>0），未锁定返回 0
     */
    private long lockedRemainingMinutes(ConcurrentHashMap<String, LoginFailInfo> map, String key, int threshold) {
        LoginFailInfo info = map.get(key);
        if (info == null || info.count.get() < threshold) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - info.lastFailTime;
        if (elapsed < LOCK_DURATION_MS) {
            return (LOCK_DURATION_MS - elapsed) / 60000 + 1;
        }
        map.remove(key);
        return 0;
    }

    /** 记录一次失败；返回该桶当前的失败次数 */
    private int recordFail(ConcurrentHashMap<String, LoginFailInfo> map, String key) {
        LoginFailInfo info = map.computeIfAbsent(key, k -> new LoginFailInfo());
        int count = info.count.incrementAndGet();
        info.lastFailTime = System.currentTimeMillis();
        return count;
    }

    /** 定期清理过期的登录失败计数，防止内存泄漏 */
    private void cleanupExpiredLoginFails() {
        long now = System.currentTimeMillis();
        cleanupMap(loginFailMap, MAX_FAIL_COUNT, now);
        cleanupMap(accountFailMap, MAX_FAIL_COUNT_PER_ACCOUNT, now);
    }

    private void cleanupMap(ConcurrentHashMap<String, LoginFailInfo> map, int threshold, long now) {
        map.entrySet().removeIf(entry -> {
            LoginFailInfo info = entry.getValue();
            // 锁定过期或 30 分钟无失败则清理
            return info.count.get() >= threshold
                    ? (now - info.lastFailTime) > LOCK_DURATION_MS
                    : (now - info.lastFailTime) > 30 * 60 * 1000L;
        });
    }

    /**
     * 定时清理登录失败计数（v1.31.3）：阻止 loginFailMap 无界增长导致内存泄漏。
     * 此前仅靠 login 请求惰性清理；若大量不同 IP 各失败不达锁定阈值，map 可持续增长。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void scheduledCleanupLoginFails() {
        try {
            cleanupExpiredLoginFails();
        } catch (Exception e) {
            log.warn("登录失败计数定时清理失败：{}", e.getMessage());
        }
    }

    /**
     * 注册
     * Body: {"username":"alice","password":"123456","email":"a@b.com"}
     * 安全加固：用户名长度 3-32，密码长度 6-64，邮箱格式校验
     */
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<String> register(@RequestBody Map<String, String> req, HttpServletRequest request) {
        String username = req.get("username");
        String password = req.get("password");
        String email    = req.get("email");

        // P2-04：注册按 IP 限流（默认 5 次/小时），防批量注册与枚举
        if (!registerLimiter.allow(com.example.interview.util.ClientIpUtil.resolve(request))) {
            return Result.error(429, "注册过于频繁，请稍后再试");
        }

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
        // 管理员保留名单不可注册：防止抢注名单用户名在签发 token 时即获得 ROLE_ADMIN
        if (jwtUtil.isAdminUsername(username)) {
            return Result.error(400, "用户名不可用");
        }
        // 密码强度校验（长度 6-64 + 弱口令/重复字符/连续序列/含用户名，见 PasswordPolicy）
        if (password.length() < 6 || password.length() > 64) {
            return Result.error(400, "密码长度需 6-64 字符");
        }
        String weakReason = com.example.interview.util.PasswordPolicy.validate(password, username);
        if (weakReason != null) {
            return Result.error(400, weakReason);
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
        try {
            userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发注册同名用户时唯一约束兜底（check-then-save 存在竞态窗口）
            return Result.error(400, "用户名已存在");
        }

        // subject 使用数据库自增 id（唯一且不可变），避免用户名变更导致 token 失效
        return Result.success(jwtUtil.generateToken(user.getId().toString(), user.getUsername()));
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
        // P1-02：账号维度计数用小写归一化键，避免攻击者靠改变大小写绕过
        String accountKey = username.trim().toLowerCase(java.util.Locale.ROOT);

        // 检查是否被锁定：IP 与账号任一维度命中即拒绝。
        // 账号维度是轮换 IP 无法绕过的兜底——这正是原实现的缺口。
        long ipLockedMin = lockedRemainingMinutes(loginFailMap, clientIp, MAX_FAIL_COUNT);
        if (ipLockedMin > 0) {
            return Result.error(429, "登录失败次数过多，请 " + ipLockedMin + " 分钟后再试");
        }
        long accountLockedMin = lockedRemainingMinutes(accountFailMap, accountKey, MAX_FAIL_COUNT_PER_ACCOUNT);
        if (accountLockedMin > 0) {
            return Result.error(429, "该账号登录失败次数过多，请 " + accountLockedMin + " 分钟后再试");
        }

        UserEntity user = userRepository.findByUsername(username)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 两个维度同时记录：IP 维度挡同一来源的批量尝试，账号维度挡跨 IP 的定向爆破
            int ipFails = recordFail(loginFailMap, clientIp);
            int accountFails = recordFail(accountFailMap, accountKey);

            // 以「更接近锁定的那一维」为准，保证提示的剩余次数与实际一致
            int remaining = Math.min(MAX_FAIL_COUNT - ipFails, MAX_FAIL_COUNT_PER_ACCOUNT - accountFails);
            if (remaining > 0) {
                return Result.error(401, "用户名或密码错误，还可尝试 " + remaining + " 次");
            } else {
                return Result.error(429, "登录失败次数过多，请 5 分钟后再试");
            }
        }

        // 登录成功，清除两个维度的失败计数，避免残留计数影响后续正常登录
        loginFailMap.remove(clientIp);
        accountFailMap.remove(accountKey);

        // v1.31.4 管理后台：被禁用的用户不允许登录
        if (userBanRegistry.isBanned(user.getId())) {
            return Result.error(403, "账号已被禁用，请联系管理员");
        }

        // subject 使用数据库自增 id（唯一且不可变），避免用户名变更导致 token 失效
        return Result.success(jwtUtil.generateToken(user.getId().toString(), user.getUsername()));
    }

    /**
     * 登出（P1 2026-09-20：登出即吊销）
     * 把当前 Bearer token 的 jti 加入进程内黑名单（存活至 token 自然过期），
     * 被盗 token 在剩余有效期内也无法再使用。
     * 无 Authorization 头 / 旧版无 jti 的 token 幂等返回成功（前端清 token 兜底）。
     */
    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout(jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && tokenBlacklist != null) {
            String token = header.substring(7);
            if (jwtUtil.isValid(token)) {
                String jti = jwtUtil.extractJti(token);
                if (jti != null) {
                    long expiresAt = jwtUtil.extractExpirationMs(token);
                    tokenBlacklist.revoke(jti, expiresAt);
                    log.info("登出：token 已吊销（jti={}，存活至 token 自然过期）", jti);
                }
            }
        }
        return Result.success(null);
    }

    /**
     * 当前登录用户信息（v1.31.4 管理后台：前端据此展示管理员入口）
     * 返回 id / username / role（ROLE_ADMIN|ROLE_USER）/ banned
     */
    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new com.example.interview.common.BusinessException("未认证用户");
        }
        String userId = auth.getPrincipal().toString();
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        boolean banned = false;
        try {
            banned = userBanRegistry.isBanned(Long.valueOf(userId));
        } catch (NumberFormatException ignored) {
            // 非数字 userId 视为未禁用
        }
        String username = "";
        try {
            UserEntity user = userRepository.findById(Long.valueOf(userId)).orElse(null);
            if (user != null) {
                username = user.getUsername();
            }
        } catch (NumberFormatException ignored) {
            // 非数字 userId 视为未禁用
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", userId);
        result.put("username", username);
        result.put("role", admin ? "ROLE_ADMIN" : "ROLE_USER");
        result.put("banned", banned);
        return Result.success(result);
    }

    /**
     * 解析客户端 IP
     * v1.23.1 安全修复：从 XFF 右端向左取第一个非受信代理 IP（此前取最左值可被伪造绕过登录锁定）
     */
    private String resolveClientIp(HttpServletRequest request) {
        return com.example.interview.util.ClientIpUtil.resolve(request);
    }
}
