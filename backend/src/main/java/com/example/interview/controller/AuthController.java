package com.example.interview.controller;

import com.example.interview.common.RateLimitExceededException;
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

    /**
     * 令牌版本缓存（P1-2，2026-10-21）：登出/改密后主动失效，使旧 token 立即被拒。
     * required=false 兼容 @WebMvcTest 切片（不加载 security 包的 @Component）。
     */
    @Autowired(required = false)
    private com.example.interview.security.TokenVersionCache tokenVersionCache;

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

    /**
     * 修改密码限流（G5，2026-10-18）：按用户维度 5 次/小时。
     *
     * <p>为什么改密也要限流：改密接口校验的是「原密码」，等价于一次离线可猜的
     * 密码校验点。拿到他人 token 的攻击者可以反复提交 <原密码, 新密码> 组合来爆破
     * 原密码；而它不像 /login 那样有 IP + 账号双维度锁定保护，必须自己限。
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.change-password-limit-per-hour:5}")
    private int changePasswordLimitPerHour;

    private com.example.interview.util.PerUserRateLimiter changePasswordLimiter;

    @jakarta.annotation.PostConstruct
    void initRegisterLimiter() {
        registerLimiter = new com.example.interview.util.PerUserRateLimiter(registerLimitPerHour, 60 * 60 * 1000L);
        changePasswordLimiter = new com.example.interview.util.PerUserRateLimiter(changePasswordLimitPerHour, 60 * 60 * 1000L);
    }

    /**
     * IP 维度：粗粒度防刷（P1-01，2026-10-30 质量循环）。
     *
     * <p><b>为什么从 5 次提到 50 次</b>：生产环境的 {@code remoteAddr} 是 Render 边缘网关的
     * 公网 IP（不是私网），而 {@code ClientIpUtil} 只在「直连是受信代理」时才解析 XFF。
     * 未配置受信代理前缀之前，<b>全站所有用户共用同一个 IP 桶</b>——
     * 阈值 5 意味着任意一个人连错 5 次密码就能锁死全站登录 5 分钟，是零成本的账号体系 DoS。
     *
     * <p>因此两个维度故意不对称：IP 维度只承担「同一来源的脚本刷量」，
     * 阈值放宽、锁定缩短；账号维度仍是 5 次 / 5 分钟，保住「定向爆破」的防线
     * （它不随 IP 轮换而失效，见 {@link #accountFailMap}）。
     * 即便平台代理段始终取证失败，这组配比也不会出现「一人锁全站」。
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.login-ip-max-fail:50}")
    private int loginIpMaxFail;

    @org.springframework.beans.factory.annotation.Value("${app.auth.login-ip-lock-seconds:60}")
    private long loginIpLockSeconds;

    /** 账号维度最大失败次数（P1-02：不随 IP 轮换而重置，见 accountFailMap 注释） */
    @org.springframework.beans.factory.annotation.Value("${app.auth.login-account-max-fail:5}")
    private int loginAccountMaxFail;

    @org.springframework.beans.factory.annotation.Value("${app.auth.login-account-lock-seconds:300}")
    private long loginAccountLockSeconds;

    /** 账号维度锁定时长（默认 5 分钟），单测/日志文案复用 */
    private long accountLockMs() {
        return Math.max(1, loginAccountLockSeconds) * 1000L;
    }

    /** IP 维度锁定时长（默认 1 分钟） */
    private long ipLockMs() {
        return Math.max(1, loginIpLockSeconds) * 1000L;
    }

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
    private long lockedRemainingMinutes(ConcurrentHashMap<String, LoginFailInfo> map, String key,
                                        int threshold, long lockDurationMs) {
        LoginFailInfo info = map.get(key);
        if (info == null || info.count.get() < threshold) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - info.lastFailTime;
        if (elapsed < lockDurationMs) {
            return (lockDurationMs - elapsed) / 60000 + 1;
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
        cleanupMap(loginFailMap, loginIpMaxFail, ipLockMs(), now);
        cleanupMap(accountFailMap, loginAccountMaxFail, accountLockMs(), now);
    }

    private void cleanupMap(ConcurrentHashMap<String, LoginFailInfo> map, int threshold,
                            long lockDurationMs, long now) {
        map.entrySet().removeIf(entry -> {
            LoginFailInfo info = entry.getValue();
            // 锁定过期或 30 分钟无失败则清理
            return info.count.get() >= threshold
                    ? (now - info.lastFailTime) > lockDurationMs
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
            throw new RateLimitExceededException("注册过于频繁，请稍后再试");
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
        // P1-2：必须带上当前的 token_version，否则登出/改密/封禁的吊销对该 token 无效
        return Result.success(jwtUtil.generateToken(
                user.getId().toString(), user.getUsername(), currentTokenVersion(user)));
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
        // 账号维度是轮换 IP 无法绕过的兜底——这正是原实现的缺口；
        // IP 维度阈值放宽（50 次 / 1 分钟锁），避免「一人连错 5 次锁死全站」。
        long ipLockedMin = lockedRemainingMinutes(loginFailMap, clientIp, loginIpMaxFail, ipLockMs());
        if (ipLockedMin > 0) {
            throw new RateLimitExceededException("登录失败次数过多，请 " + ipLockedMin + " 分钟后再试");
        }
        long accountLockedMin = lockedRemainingMinutes(
                accountFailMap, accountKey, loginAccountMaxFail, accountLockMs());
        if (accountLockedMin > 0) {
            throw new RateLimitExceededException("该账号登录失败次数过多，请 " + accountLockedMin + " 分钟后再试");
        }

        UserEntity user = userRepository.findByUsername(username)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // 两个维度同时记录：IP 维度挡同一来源的批量尝试，账号维度挡跨 IP 的定向爆破
            int ipFails = recordFail(loginFailMap, clientIp);
            int accountFails = recordFail(accountFailMap, accountKey);

            // P1-01：登录失败必须留下可审计的「解析后 IP + 原始 XFF」。
            // 受信代理未配置时两者会不一致，这正是后续校正网段的唯一线索。
            log.warn("登录失败 ip={} account={} xff={}", clientIp, accountKey, clipHeader(request));

            // 以「更接近锁定的那一维」为准，保证提示的剩余次数与实际一致。
            // 账号维度阈值远严于 IP 维度，正常情况下来自账号维度的剩余次数更小。
            int remaining = Math.min(loginIpMaxFail - ipFails, loginAccountMaxFail - accountFails);
            if (remaining > 0) {
                return Result.error(401, "用户名或密码错误，还可尝试 " + remaining + " 次");
            } else if (accountFails >= loginAccountMaxFail) {
                throw new RateLimitExceededException("该账号登录失败次数过多，请 5 分钟后再试");
            } else {
                // 走到这里说明触发的是 IP 粗粒度桶（锁定只有 1 分钟，不能照抄账号维度的文案）
                throw new RateLimitExceededException("登录失败次数过多，请稍后再试");
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
        // P1-2：必须带上当前的 token_version，否则登出/改密/封禁的吊销对该 token 无效
        return Result.success(jwtUtil.generateToken(
                user.getId().toString(), user.getUsername(), currentTokenVersion(user)));
    }

    /**
     * 登出（P1-2，2026-10-21：令牌版本自增，全设备失效）
     *
     * <p>把当前用户的 {@code token_version} +1，使其此前签发的<b>所有</b> token 立即失效。
     * 这替代了原来的 jti 黑名单 —— 黑名单存在进程内 Map 里，重启即丢（被登出的 token 复活
     * 最长 24 小时），而版本号跟着用户行走数据库，重启不影响。
     *
     * <p>未认证 / 匿名请求幂等返回成功（前端已清 token 兜底）。
     */
    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        Long userId = currentUserIdOrNull();
        if (userId != null) {
            bumpTokenVersion(userId, "登出");
        }
        return Result.success(null);
    }

    /**
     * 使某用户的全部已签发 token 失效（P1-2）。
     *
     * <p>两步都要做：DB 的 {@code token_version} +1 是<b>持久化的事实</b>（重启/多实例都认），
     * 清本地缓存是为了<b>让本实例立刻生效</b>，否则用户登出后最坏还要等 60 秒
     * （{@code TokenVersionCache.TTL_MS}）才被拒绝。
     */
    private void bumpTokenVersion(Long userId, String reason) {
        int affected = userRepository.incrementTokenVersion(userId);
        if (tokenVersionCache != null) {
            tokenVersionCache.invalidate(userId);
        }
        log.info("{}：已使 userId={} 的全部 token 失效（token_version+1，影响 {} 行）", reason, userId, affected);
    }

    /**
     * 取实体的令牌版本，null 时回退到默认值 1（与 DB 默认值一致）。
     * 防御历史数据：理论上 `token_version` 是 NOT NULL，但 Upgrade 前的连接池缓存/手工插入
     * 都可能给出 null，此时签出 1 总比 NPE 或签出 0（必然被拒）好。
     */
    private int currentTokenVersion(UserEntity user) {
        Integer version = user.getTokenVersion();
        return version == null ? com.example.interview.security.JwtUtil.DEFAULT_TOKEN_VERSION : version;
    }

    /** 从 SecurityContext 取当前用户 id；未认证 / 匿名 / 非数字一律返回 null */
    private Long currentUserIdOrNull() {        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String raw = principal == null ? null : principal.toString();
        if (raw == null || !raw.matches("\\d+")) {
            return null;
        }
        return Long.valueOf(raw);
    }

    /**
     * 修改密码（G5，2026-10-18）。
     *
     * <p>背景：改密前的 AuthController 只有 register / login / logout / me 四个端点，
     * 用户一旦忘记密码，账号连同其投递台账、面试记录、故事库**永久无法取回**。
     * 本接口先解决「记得原密码时主动改密」，找回密码依赖邮件通道、另行排期。
     *
     * <p>安全约束（缺一即成为攻击面）：
     * <ol>
     *   <li>原密码必须校验 —— 仅凭 token 不足以改密；</li>
     *   <li>按用户 5 次/小时限流 —— 防止拿到 token 后爆破原密码；</li>
     *   <li>新密码走与注册同一套 PasswordPolicy —— 否则可绕过弱口令校验；</li>
     *   <li>改完<b>吊销该用户的全部 token</b>（token_version+1）—— 改密的语义就是
     *       「让旧会话失效」，不吊销等于 attacker 拿到 token 后改密也能继续用。</li>
     * </ol>
     *
     * <p>P1-2（2026-10-21）：吊销范围由「仅当前 token（jti 黑名单）」升级为
     * <b>该用户全部设备</b>（token_version+1）。这是刻意取舍——改密后把所有会话踢下线
     * 更安全；语义代价是「某台设备改密会让其它设备的会话也失效」，对单用户场景可接受。
     */
    @Operation(summary = "修改密码（凭原密码）")
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody Map<String, String> req) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        // 不能只判 `auth.getPrincipal() != null`：Spring Security 的匿名令牌
        // AnonymousAuthenticationToken 的 isAuthenticated() 恒为 true、principal 是字符串
        // "anonymousUser"，会让匿名请求一路走到业务校验（线上实测返回
        // 400「新密码长度需 6-64 字符」，而不是 401/503）。
        // JWT 的 subject 恒为数据库自增 id，故「非数字 principal」可直接判定为未认证。
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new com.example.interview.common.BusinessException("未认证用户");
        }
        Object principal = auth.getPrincipal();
        String userId = principal == null ? null : principal.toString();
        if (userId == null || !userId.matches("\\d+")) {
            throw new com.example.interview.common.BusinessException("未认证用户");
        }

        if (!changePasswordLimiter.allow(userId)) {
            throw new RateLimitExceededException("修改密码过于频繁，请稍后再试");
        }

        String oldPassword = req.get("oldPassword");
        String newPassword = req.get("newPassword");
        if (oldPassword == null || oldPassword.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            return Result.error(400, "原密码与新密码不能为空");
        }
        // 长度上限先挡住，避免超长字符串进入 BCrypt（BCrypt 只取前 72 字节，超长会被静默截断）
        if (oldPassword.length() > 128) {
            return Result.error(400, "原密码长度超限");
        }
        if (newPassword.length() < 6 || newPassword.length() > 64) {
            return Result.error(400, "新密码长度需 6-64 字符");
        }

        UserEntity user;
        try {
            user = userRepository.findById(Long.valueOf(userId)).orElse(null);
        } catch (NumberFormatException e) {
            user = null;   // 非数字 subject（历史 token）一律视为用户不存在
        }
        if (user == null) {
            return Result.error(400, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            return Result.error(400, "原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return Result.error(400, "新密码不能与原密码相同");
        }
        String weakReason = com.example.interview.util.PasswordPolicy.validate(newPassword, user.getUsername());
        if (weakReason != null) {
            return Result.error(400, weakReason);
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.saveAndFlush(user);

        // P1-2：改密后该用户所有设备的 token 全部失效（含当前这台）
        bumpTokenVersion(user.getId(), "改密");

        log.info("用户 {} 修改密码成功，全部已签发 token 已失效（token_version+1）", user.getUsername());
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

    /** 日志里的 XFF 截断到 200 字符：该头由客户端可写，不应成为日志膨胀/注入的入口 */
    private static String clipHeader(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return "-";
        }
        return xff.length() <= 200 ? xff : xff.substring(0, 200) + "…";
    }
}
