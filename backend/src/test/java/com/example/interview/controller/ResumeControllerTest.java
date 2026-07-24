package com.example.interview.controller;

import com.example.interview.entity.ResumeEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ResumeController} MockMvc 集成测试
 *
 * <p>覆盖 6 个端点：analyze / optimize / import-url / upload / history / getById。
 * - analyze/optimize：入参校验
 * - import-url：SSRF 防御（内网地址拦截）
 * - upload：MultipartFile 全链路校验（空文件/超大/扩展名/Content-Type/PDF magic bytes/解析成功失败）
 * - history/getById：列表查询 + IDOR 越权防御（非本人简历返回 400）
 */
@WebMvcTest(controllers = ResumeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class ResumeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResumeAnalysisService resumeAnalysisService;

    @MockBean
    private ResumeParseService resumeParseService;

    @MockBean
    private ResumeService resumeService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUpSecurityContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        // 24 个用例超出 10 次/分钟限流阈值，mock preHandle 绕过
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /api/resume/analyze 简历分析")
    class Analyze {

        @Test
        @DisplayName("简历内容为空返回 400")
        void analyze_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("简历内容缺失返回 400")
        void analyze_missingResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 分析结果")
        void analyze_validInput_returns200() throws Exception {
            when(resumeAnalysisService.analyze(USER_ID, "我的简历", "Java 后端"))
                    .thenReturn("{\"score\":80}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/resume/optimize 优化简历生成")
    class Optimize {

        @Test
        @DisplayName("简历与分析均为空返回 400（简历优先）")
        void optimize_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "analysis", ""));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空，请先上传简历或粘贴文本完成分析"));
        }

        @Test
        @DisplayName("简历存在但分析为空返回 400")
        void optimize_emptyAnalysis_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "analysis", ""));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请先完成简历分析，再生成优化版简历"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 优化简历 Markdown")
        void optimize_validInput_returns200() throws Exception {
            when(resumeAnalysisService.generateOptimizedResume(
                    USER_ID, "我的简历", "Java 后端", "{\"score\":80}"))
                    .thenReturn("# 优化后的简历\n\n正文…");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历",
                    "targetJob", "Java 后端",
                    "analysis", "{\"score\":80}"));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/resume/import-url URL 导入（SSRF 防御）")
    class ImportUrl {

        @Test
        @DisplayName("URL 为空返回 400")
        void importUrl_emptyUrl_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", ""));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("URL 不能为空"));
        }

        @Test
        @DisplayName("非 http/https 协议返回 400")
        void importUrl_invalidProtocol_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "ftp://example.com/resume"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请输入有效的 URL（以 http:// 或 https:// 开头）"));
        }

        @Test
        @DisplayName("内网地址 127.0.0.1 被拦截返回 400")
        void importUrl_localhost_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "http://127.0.0.1/admin"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("不支持访问内网地址"));
        }

        @Test
        @DisplayName("内网地址 192.168.x 被拦截返回 400")
        void importUrl_privateNetwork_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "http://192.168.1.1/internal"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("不支持访问内网地址"));
        }
    }

    @Nested
    @DisplayName("POST /api/resume/upload 文件上传")
    class Upload {

        @Test
        @DisplayName("空文件返回 400")
        void upload_emptyFile_returns400() throws Exception {
            MockMultipartFile empty = new MockMultipartFile(
                    "file", "empty.txt", "text/plain", new byte[0]);

            mockMvc.perform(multipart("/api/resume/upload").file(empty))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请上传简历文件"));
        }

        @Test
        @DisplayName("文件超过 10MB 返回 400")
        void upload_oversizeFile_returns400() throws Exception {
            // 10MB + 1 字节，触发 size > 10*1024*1024 分支
            MockMultipartFile big = new MockMultipartFile(
                    "file", "big.txt", "text/plain", new byte[10 * 1024 * 1024 + 1]);

            mockMvc.perform(multipart("/api/resume/upload").file(big))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文件大小不能超过 10MB"));
        }

        @Test
        @DisplayName("文件名为空返回 400")
        void upload_blankFilename_returns400() throws Exception {
            // MockMultipartFile 需非空 content 才能通过 isEmpty 校验，但 originalFilename 为空
            MockMultipartFile noName = new MockMultipartFile(
                    "file", "", "text/plain", "内容".getBytes());

            mockMvc.perform(multipart("/api/resume/upload").file(noName))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文件名不能为空"));
        }

        @Test
        @DisplayName("不支持的扩展名（.docx）返回 400")
        void upload_unsupportedExt_returns400() throws Exception {
            MockMultipartFile docx = new MockMultipartFile(
                    "file", "resume.docx", "application/octet-stream", "内容".getBytes());

            mockMvc.perform(multipart("/api/resume/upload").file(docx))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value(
                            "仅支持 PDF / HTML / MD / TXT 格式的简历文件（Word 请转换为 PDF）"));
        }

        @Test
        @DisplayName("非法 Content-Type（image/jpeg）返回 400")
        void upload_invalidContentType_returns400() throws Exception {
            // 扩展名合法（.txt）但 CT 非法，验证 CT 白名单独立生效
            MockMultipartFile badCt = new MockMultipartFile(
                    "file", "resume.txt", "image/jpeg", "内容".getBytes());

            mockMvc.perform(multipart("/api/resume/upload").file(badCt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文件类型不受支持"));
        }

        @Test
        @DisplayName("PDF magic bytes 不匹配返回 400")
        void upload_invalidPdfMagic_returns400() throws Exception {
            // 文件名 .pdf 但内容不以 %PDF- 开头，验证 magic bytes 校验
            MockMultipartFile fakePdf = new MockMultipartFile(
                    "file", "resume.pdf", "application/pdf", "这不是PDF".getBytes());

            mockMvc.perform(multipart("/api/resume/upload").file(fakePdf))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("文件内容不是合法的 PDF"));
        }

        @Test
        @DisplayName("合法 TXT 上传返回 200 + analysis + resumeText")
        void upload_validTxt_returns200() throws Exception {
            MockMultipartFile txt = new MockMultipartFile(
                    "file", "resume.txt", "text/plain", "我的简历内容".getBytes());

            when(resumeParseService.parseToText(any(MultipartFile.class))).thenReturn("我的简历内容");
            when(resumeAnalysisService.analyze(eq(USER_ID), eq("我的简历内容"), eq("Java 后端")))
                    .thenReturn("{\"score\":80}");

            mockMvc.perform(multipart("/api/resume/upload")
                            .file(txt)
                            .param("targetJob", "Java 后端"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.analysis").value("{\"score\":80}"))
                    .andExpect(jsonPath("$.data.resumeText").value("我的简历内容"));
        }

        @Test
        @DisplayName("合法 PDF（magic bytes 正确）上传返回 200")
        void upload_validPdf_returns200() throws Exception {
            // %PDF- 开头通过 magic bytes 校验
            byte[] pdfBytes = "%PDF-1.4\n模拟PDF内容".getBytes();
            MockMultipartFile pdf = new MockMultipartFile(
                    "file", "resume.pdf", "application/pdf", pdfBytes);

            when(resumeParseService.parseToText(any(MultipartFile.class))).thenReturn("解析出的简历文本");
            when(resumeAnalysisService.analyze(eq(USER_ID), eq("解析出的简历文本"), eq("通用岗位")))
                    .thenReturn("{\"score\":90}");

            mockMvc.perform(multipart("/api/resume/upload").file(pdf))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.resumeText").value("解析出的简历文本"));
        }

        @Test
        @DisplayName("解析抛 IllegalArgumentException 返回 400 + 错误消息")
        void upload_parseIllegalArg_returns400() throws Exception {
            MockMultipartFile txt = new MockMultipartFile(
                    "file", "resume.txt", "text/plain", "内容".getBytes());

            when(resumeParseService.parseToText(any(MultipartFile.class)))
                    .thenThrow(new IllegalArgumentException("简历文件内容为空，请检查文件是否损坏"));

            mockMvc.perform(multipart("/api/resume/upload").file(txt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历文件内容为空，请检查文件是否损坏"));
        }

        @Test
        @DisplayName("解析抛其他异常返回 500 兜底提示")
        void upload_parseUnexpected_returns500() throws Exception {
            MockMultipartFile txt = new MockMultipartFile(
                    "file", "resume.txt", "text/plain", "内容".getBytes());

            when(resumeParseService.parseToText(any(MultipartFile.class)))
                    .thenThrow(new RuntimeException("IO 崩了"));

            mockMvc.perform(multipart("/api/resume/upload").file(txt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("简历解析失败，请检查文件是否损坏或重试"));
        }
    }

    @Nested
    @DisplayName("GET /api/resume/history & /api/resume/{id} 历史与详情")
    class HistoryAndGetById {

        @Test
        @DisplayName("history 返回当前用户简历列表")
        void history_returnsList() throws Exception {
            ResumeEntity r1 = ResumeEntity.builder()
                    .id(1L).userId(USER_ID).content("简历1").targetJob("Java 后端")
                    .overallScore(80).createdAt(LocalDateTime.now()).build();
            when(resumeService.listByUser(USER_ID)).thenReturn(List.of(r1));

            mockMvc.perform(get("/api/resume/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].content").value("简历1"));
        }

        @Test
        @DisplayName("history 空列表返回 200 + []")
        void history_emptyList_returns200() throws Exception {
            when(resumeService.listByUser(USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/resume/history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("getById 返回本人简历详情")
        void getById_ownResume_returns200() throws Exception {
            ResumeEntity r = ResumeEntity.builder()
                    .id(1L).userId(USER_ID).content("我的简历").targetJob("Java 后端")
                    .overallScore(75).createdAt(LocalDateTime.now()).build();
            when(resumeService.getByIdAndUser(1L, USER_ID)).thenReturn(r);

            mockMvc.perform(get("/api/resume/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.content").value("我的简历"));
        }

        @Test
        @DisplayName("getById 越权访问他人简历返回 400（IDOR 防御）")
        void getById_otherUserResume_returns400() throws Exception {
            // Service 层 getByIdAndUser 在 userId 不匹配时抛 IllegalArgumentException
            // GlobalExceptionHandler @ResponseStatus(BAD_REQUEST) 使 HTTP 400
            // （语义上 403 更准确，此处测试覆盖现状）
            when(resumeService.getByIdAndUser(1L, USER_ID))
                    .thenThrow(new IllegalArgumentException("简历不存在或无权访问"));

            mockMvc.perform(get("/api/resume/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请求参数错误：简历不存在或无权访问"));
        }
    }
}
