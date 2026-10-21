package com.example.interview.security;

import com.example.interview.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthFilter} 的令牌版本校验测试（P1-2，2026-10-21）。
 *
 * <h2>为什么这条链路必须有测试</h2>
 *
 * <p>登出/改密/封禁的吊销能力全部落在「token 里的 {@code ver} 与 DB 的
 * {@code token_version} 是否相等」这一次比对里。比错了方向（等于改成不等于、或
 * 无 claim 一律拒绝），后果分别是「谁都踢不掉」与「升级瞬间全员掉线」——
 * 两者都不会让其它测试变红，只能靠这里锁住。
 *
 * <h2>兼容矩阵（本类的核心）</h2>
 *
 * <table>
 *   <tr><th>token.ver</th><th>db.token_version</th><th>结果</th><th>为什么</th></tr>
 *   <tr><td>1</td><td>1</td><td>认证</td><td>正常</td></tr>
 *   <tr><td>1</td><td>2</td><td>不认证</td><td>已登出/改密/封禁</td></tr>
 *   <tr><td>无 claim</td><td>1</td><td>认证</td><td>升级兼容：视为 1</td></tr>
 *   <tr><td>无 claim</td><td>2</td><td>不认证</td><td>旧 token 在该用户 +1 后失效</td></tr>
 * </table>
 */
@DisplayName("JwtAuthFilter 令牌版本校验（P1-2）")
class JwtAuthFilterTokenVersionTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    private JwtUtil jwtUtil;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 600_000L);
        ReflectionTestUtils.setField(jwtUtil, "adminUsernames", "");
        jwtUtil.validateSecret();

        filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtUtil", jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────── 测试脚手架 ───────────────────────────

    /** 造一个「DB 里 token_version = dbVersion」的缓存组件 */
    private TokenVersionCache cacheWithVersion(long userId, int dbVersion) {
        TokenVersionCache cache = new TokenVersionCache();
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById(userId)).thenReturn(Optional.of(
                com.example.interview.entity.UserEntity.builder()
                        .id(userId).username("u").passwordHash("h").tokenVersion(dbVersion).build()));
        ReflectionTestUtils.setField(cache, "userRepository", repository);
        ReflectionTestUtils.setField(filter, "tokenVersionCache", cache);
        return cache;
    }

    private boolean runWithToken(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /** 走 3 参签发（带 ver claim） */
    private String tokenWithVersion(int ver) {
        return jwtUtil.generateToken("1", "alice", ver);
    }

    /** 造一个合法签名但没有 ver claim 的「升级前」token */
    private String legacyTokenWithoutVersion() {
        return io.jsonwebtoken.Jwts.builder()
                .subject("1")
                .issuer("interview-guide")
                .audience().add("interview-guide-client").and()
                .claim("role", "ROLE_USER")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 600_000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    // ─────────────────────────── 兼容矩阵 ───────────────────────────

    @Nested
    @DisplayName("版本比对矩阵")
    class VersionMatrix {

        @Test
        @DisplayName("ver 与 DB 一致 → 认证")
        void versionMatches_authenticates() throws Exception {
            cacheWithVersion(1L, 1);
            assertThat(runWithToken(tokenWithVersion(1))).isTrue();
        }

        @Test
        @DisplayName("ver 与 DB 不一致（已登出/改密/封禁）→ 不认证")
        void versionMismatch_doesNotAuthenticate() throws Exception {
            cacheWithVersion(1L, 2);
            assertThat(runWithToken(tokenWithVersion(1))).isFalse();
        }

        @Test
        @DisplayName("无 ver claim 且 DB version=1 → 认证（升级瞬间不踢人）")
        void legacyTokenDbVersion1_authenticates() throws Exception {
            cacheWithVersion(1L, 1);
            assertThat(runWithToken(legacyTokenWithoutVersion())).isTrue();
        }

        @Test
        @DisplayName("无 ver claim 但 DB version 已 +1 → 不认证（旧 token 在吊销后失效）")
        void legacyTokenAfterBump_doesNotAuthenticate() throws Exception {
            cacheWithVersion(1L, 2);
            assertThat(runWithToken(legacyTokenWithoutVersion())).isFalse();
        }

        @Test
        @DisplayName("version 比 DB 更新（伪造/回滚）→ 不认证")
        void versionAheadOfDb_doesNotAuthenticate() throws Exception {
            cacheWithVersion(1L, 1);
            assertThat(runWithToken(tokenWithVersion(9))).isFalse();
        }

        @Test
        @DisplayName("切片场景无 TokenVersionCache → 跳过版本校验（否则全部 @WebMvcTest 会挂）")
        void noCacheBean_skipsVersionCheck() throws Exception {
            ReflectionTestUtils.setField(filter, "tokenVersionCache", null);
            assertThat(runWithToken(tokenWithVersion(5))).isTrue();
        }
    }

    // ─────────────────────────── 缓存行为 ───────────────────────────

    @Nested
    @DisplayName("缓存：命中不查库、过期重查、invalidate 立即重查")
    class Caching {

        @Test
        @DisplayName("TTL 内多次校验只查库一次（这是全站必经路径，不能每请求一次 DB）")
        void withinTtl_queriesDbOnce() {
            TokenVersionCache cache = new TokenVersionCache();
            UserRepository repository = mock(UserRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(
                    com.example.interview.entity.UserEntity.builder()
                            .id(1L).username("u").passwordHash("h").tokenVersion(1).build()));
            ReflectionTestUtils.setField(cache, "userRepository", repository);

            long[] now = {1_000L};
            cache.setClock(() -> now[0]);

            assertThat(cache.currentVersion(1L)).isEqualTo(1);
            now[0] += TokenVersionCache.TTL_MS - 1;
            assertThat(cache.currentVersion(1L)).isEqualTo(1);
            assertThat(cache.currentVersion(1L)).isEqualTo(1);

            verify(repository, times(1)).findById(1L);
        }

        @Test
        @DisplayName("TTL 过期后重新查库（多实例/外部改库的兜底）")
        void afterTtl_requeriesDb() {
            TokenVersionCache cache = new TokenVersionCache();
            UserRepository repository = mock(UserRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(
                    com.example.interview.entity.UserEntity.builder()
                            .id(1L).username("u").passwordHash("h").tokenVersion(1).build()));
            ReflectionTestUtils.setField(cache, "userRepository", repository);

            long[] now = {1_000L};
            cache.setClock(() -> now[0]);

            cache.currentVersion(1L);
            now[0] += TokenVersionCache.TTL_MS + 1;
            cache.currentVersion(1L);

            verify(repository, times(2)).findById(1L);
        }

        @Test
        @DisplayName("invalidate 后立刻重查（登出即时生效，不必等 TTL）")
        void invalidate_forcesRequery() {
            TokenVersionCache cache = new TokenVersionCache();
            UserRepository repository = mock(UserRepository.class);
            when(repository.findById(1L)).thenReturn(Optional.of(
                    com.example.interview.entity.UserEntity.builder()
                            .id(1L).username("u").passwordHash("h").tokenVersion(1).build()));
            ReflectionTestUtils.setField(cache, "userRepository", repository);

            cache.currentVersion(1L);
            cache.invalidate(1L);
            cache.currentVersion(1L);

            verify(repository, times(2)).findById(1L);
            assertThat(cache.cachedSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("查库异常 → 返回 null（跳过校验）而不是把全站打成 401")
        void repositoryFailure_returnsNull() {
            TokenVersionCache cache = new TokenVersionCache();
            UserRepository repository = mock(UserRepository.class);
            when(repository.findById(1L)).thenThrow(new RuntimeException("db down"));
            ReflectionTestUtils.setField(cache, "userRepository", repository);

            assertThat(cache.currentVersion(1L)).isNull();
        }

        @Test
        @DisplayName("用户不存在 → 返回 null，且不缓存「查不到」")
        void userMissing_returnsNullAndDoesNotCache() {
            TokenVersionCache cache = new TokenVersionCache();
            UserRepository repository = mock(UserRepository.class);
            when(repository.findById(99L)).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(cache, "userRepository", repository);

            assertThat(cache.currentVersion(99L)).isNull();
            assertThat(cache.cachedSize()).isZero();
        }

        @Test
        @DisplayName("TTL 常量是 60 秒 —— 它同时是「多实例下登出最坏延迟」的上限，改动须同步文档")
        void ttlIs60Seconds() {
            assertThat(TokenVersionCache.TTL_MS).isEqualTo(60_000L);
        }
    }
}
