package com.example.interview.security;

import com.example.interview.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登出吊销的端到端验证（P1-2，2026-10-21）。
 *
 * <h2>为什么切片测试不够，必须起完整上下文</h2>
 *
 * <p>「登出后旧 token 还能用」是本轮要修的缺陷本身。它在切片测试里做不出来：
 * {@code @WebMvcTest} 没有真实的 {@link JwtAuthFilter} 依赖（{@code UserRepository}、
 * {@code TokenVersionCache} 都被 mock 掉），版本比对这一段根本不会真正执行。
 * 只有起完整上下文（真实 Filter 链 + 真实 H2 + 真实 JWT），才能验证
 * 「同一串 JWT 在登出前后从 200 变成 401」——这才是用户实际会遇到的行为。
 *
 * <p>断言链：
 * <ol>
 *   <li>注册拿到 token → {@code GET /api/auth/me} 200（token 本来可用）；</li>
 *   <li>{@code POST /api/auth/logout} 200，且 DB 里 {@code token_version} 由 1 变 2；</li>
 *   <li>用<b>同一个 token</b> 再请求 → <b>401</b>（吊销生效，无需等 token 自然过期）；</li>
 *   <li>重新登录拿到的新 token → 200（吊销没有把账号本身弄坏）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 与 ApplicationContextSmokeTest 同款隔离：内存库 + 关闭向量库持久化与播种
        "spring.datasource.url=jdbc:h2:mem:tokenversion;DB_CLOSE_DELAY=-1",
        "app.rag.persist-enabled=false",
        "app.rag.seed-enabled=false",
        "app.rag.snapshot-file=${java.io.tmpdir}/interview-tokenversion-vectorstore.json"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("登出吊销端到端（P1-2）")
class TokenVersionRevocationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    @Order(1)
    @DisplayName("登出后同一 token 立刻 401，重新登录后新 token 可用")
    void logout_revokesAllPreviouslyIssuedTokens() throws Exception {
        // 用户名唯一：本类若被重复执行/与其它上下文共用同名库也不会撞唯一约束
        String username = "rvk" + (System.nanoTime() % 100_000_000L);
        String password = "Qa#Str0ngPass2026";

        String token = register(username, password);
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();

        // ① 登出前：token 可用
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(userRepository.findById(userId).orElseThrow().getTokenVersion())
                .as("注册后版本应为 1").isEqualTo(1);

        // ② 登出
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(userRepository.findById(userId).orElseThrow().getTokenVersion())
                .as("登出必须把 token_version 持久化 +1（这是重启后依然生效的前提）").isEqualTo(2);

        // ③ 同一 token 立刻失效（401 来自 Security 的 authenticationEntryPoint）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        // ④ 重新登录得到的新 token 带 ver=2，可以正常使用（吊销没有损坏账号）
        String newToken = login(username, password);
        assertThat(newToken).isNotEqualTo(token);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private String register(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return tokenOf(result);
    }

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return tokenOf(result);
    }

    private static String tokenOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        Object data = parsed.get("data");
        assertThat(data).as("注册/登录响应必须带 token：%s", json).isInstanceOf(String.class);
        return (String) data;
    }
}
