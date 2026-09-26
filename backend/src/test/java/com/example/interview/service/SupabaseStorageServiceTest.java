package com.example.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link SupabaseStorageService} 单元测试
 *
 * <p>覆盖核心方法 {@code upload}：
 * <ul>
 *   <li>文件名清洗：特殊字符替换为下划线，防路径穿越</li>
 *   <li>HTTP 请求头：Authorization Bearer、Content-Type、x-upsert</li>
 *   <li>HTTP 响应：2xx 成功返回公开 URL、非 2xx 抛 RuntimeException</li>
 *   <li>文件 contentType 为 null 时 fallback 到 application/octet-stream</li>
 *   <li>URL 拼接：上传 URL vs 公开访问 URL</li>
 * </ul>
 *
 * <p>Mock 策略：
 * <ul>
 *   <li>RestTemplate：通过反射注入（原构造函数 new 出来，测试时替换为 mock）</li>
 *   <li>@Value 字段（supabaseUrl/serviceKey/bucket）：通过 ReflectionTestUtils 注入</li>
 *   <li>MultipartFile：mock getBytes/getContentType/getOriginalFilename</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupabaseStorageService 单元测试")
class SupabaseStorageServiceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private MultipartFile file;

    @InjectMocks
    private SupabaseStorageService service;

    private static final String SUPABASE_URL = "https://test.supabase.co";
    private static final String SERVICE_KEY = "test-service-key";
    private static final String BUCKET = "resumes";
    private static final byte[] FILE_BYTES = "resume content".getBytes();

    @BeforeEach
    void setUp() throws IOException {
        // 注入 @Value 字段
        ReflectionTestUtils.setField(service, "supabaseUrl", SUPABASE_URL);
        ReflectionTestUtils.setField(service, "serviceKey", SERVICE_KEY);
        ReflectionTestUtils.setField(service, "bucket", BUCKET);
        // 注入 mock RestTemplate（替换构造函数 new 出来的实例）
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        // MultipartFile 默认行为
        when(file.getBytes()).thenReturn(FILE_BYTES);
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getOriginalFilename()).thenReturn("resume.pdf");

        // RestTemplate 默认返回 200 成功
        when(restTemplate.exchange(
                anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
        )).thenReturn(new ResponseEntity<>("{\"ok\":true}", HttpStatus.OK));
    }

    @Nested
    @DisplayName("upload: 文件上传")
    class Upload {

        @Test
        @DisplayName("正常上传返回公开访问 URL")
        void upload_validFile_returnsPublicUrl() throws IOException {
            String result = service.upload(file, "resume.pdf");

            assertThat(result).isEqualTo(
                    SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/resume.pdf");
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("文件名含特殊字符时清洗为下划线")
        void upload_fileNameWithSpecialChars_sanitized() throws IOException {
            // 含路径穿越字符和中文
            String result = service.upload(file, "../../etc/passwd/简历.pdf");

            // 验证返回 URL 中文件名已被清洗
            assertThat(result).contains("_etc_passwd_");
            // 验证上传 URL 也用了清洗后的文件名（通过 captor 捕获）
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
            assertThat(urlCaptor.getValue()).contains("_etc_passwd_");
            assertThat(urlCaptor.getValue()).doesNotContain("../../");
        }

        @Test
        @DisplayName("HTTP 响应非 2xx 时抛 RuntimeException")
        void upload_non2xxResponse_throwsRuntimeException() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>("upload failed", HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Supabase 文件上传失败");
        }

        @Test
        @DisplayName("HTTP 401 响应抛 RuntimeException")
        void upload_unauthorizedResponse_throwsRuntimeException() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>("unauthorized", HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Supabase 文件上传失败");
        }

        @Test
        @DisplayName("HTTP 400 响应抛 RuntimeException")
        void upload_badRequestResponse_throwsRuntimeException() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>("bad request", HttpStatus.BAD_REQUEST));

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Supabase 文件上传失败");
        }

        @Test
        @DisplayName("文件 contentType 为 null 时 fallback 到 application/octet-stream")
        void upload_nullContentType_fallbackToOctetStream() throws IOException {
            when(file.getContentType()).thenReturn(null);

            service.upload(file, "resume.pdf");

            // 验证请求头 Content-Type 为 application/octet-stream
            ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), entityCaptor.capture(), eq(String.class));
            HttpHeaders headers = entityCaptor.getValue().getHeaders();
            assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        }

        @Test
        @DisplayName("请求头包含 Authorization Bearer + x-upsert=true")
        void upload_setsCorrectHeaders() throws IOException {
            service.upload(file, "resume.pdf");

            ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), entityCaptor.capture(), eq(String.class));
            HttpHeaders headers = entityCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer " + SERVICE_KEY);
            assertThat(headers.getFirst("x-upsert")).isEqualTo("true");
        }

        @Test
        @DisplayName("上传 URL 使用 PUT 方法 + 正确路径")
        void upload_usesPutMethodAndCorrectPath() throws IOException {
            service.upload(file, "my-resume.pdf");

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class));
            assertThat(urlCaptor.getValue())
                    .isEqualTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/my-resume.pdf");
        }

        @Test
        @DisplayName("上传请求体为 ByteArrayResource")
        void upload_requestBodyIsByteArrayResource() throws IOException {
            service.upload(file, "resume.pdf");

            ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), entityCaptor.capture(), eq(String.class));
            assertThat(entityCaptor.getValue().getBody()).isInstanceOf(ByteArrayResource.class);
        }
    }

    @Nested
    @DisplayName("isOwnPublicUrl: 附图存储域白名单（P1-08）")
    class IsOwnPublicUrl {

        @Test
        @DisplayName("本系统 Supabase 公共对象 URL 放行")
        void ownPublicUrl_pass() {
            assertThat(service.isOwnPublicUrl(
                    SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/img.png")).isTrue();
        }

        @Test
        @DisplayName("同域但非 public 对象路径（如上传端点路径）不视为公共附图")
        void uploadPath_fail() {
            assertThat(service.isOwnPublicUrl(
                    SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/img.png")).isFalse();
        }

        @Test
        @DisplayName("其他域名/内网元数据地址一律拒绝")
        void foreignHost_fail() {
            assertThat(service.isOwnPublicUrl(
                    "https://evil.example.com/storage/v1/object/public/resumes/a.png")).isFalse();
            assertThat(service.isOwnPublicUrl(
                    "http://169.254.169.254/storage/v1/object/public/resumes/a.png")).isFalse();
        }

        @Test
        @DisplayName("空值安全返回 false")
        void nullSafe_fail() {
            assertThat(service.isOwnPublicUrl(null)).isFalse();
            assertThat(service.isOwnPublicUrl("")).isFalse();
        }
    }

    @Nested
    @DisplayName("createSignedUrl: 短时效签名 URL（P2-06）")
    class CreateSignedUrl {

        private static final String OWN_PUBLIC_URL =
                SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/interview/u1/1.png";

        @Test
        @DisplayName("本系统公开 URL 换签成功：返回带 token 的签名 URL")
        void sign_ownUrl_returnsSignedUrl() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>(
                    "{\"signedURL\":\"/object/sign/" + BUCKET + "/interview/u1/1.png?token=tok\"}",
                    HttpStatus.OK));

            String result = service.createSignedUrl(OWN_PUBLIC_URL, 600);

            assertThat(result).isEqualTo(SUPABASE_URL + "/storage/v1/object/sign/"
                    + BUCKET + "/interview/u1/1.png?token=tok");
            verify(restTemplate).exchange(
                    eq(SUPABASE_URL + "/storage/v1/object/sign/" + BUCKET + "/interview/u1/1.png"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("签名请求携带 service key 与 expiresIn 请求体")
        void sign_sendsServiceKeyAndTtl() {
            service.createSignedUrl(OWN_PUBLIC_URL, 600);

            ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
            HttpHeaders headers = entityCaptor.getValue().getHeaders();
            assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer " + SERVICE_KEY);
            assertThat(entityCaptor.getValue().getBody()).isEqualTo("{\"expiresIn\":600}");
        }

        @Test
        @DisplayName("非本系统 URL 直接原样返回（不发起请求）")
        void sign_foreignUrl_returnsOriginal() {
            String foreign = "https://evil.example.com/storage/v1/object/public/resumes/a.png";

            String result = service.createSignedUrl(foreign, 600);

            assertThat(result).isEqualTo(foreign);
            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class),
                    any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("签名接口失败时回退原公开 URL（bucket 公读期间平滑过渡）")
        void sign_httpError_fallsBackToPublicUrl() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>("sign failed", HttpStatus.INTERNAL_SERVER_ERROR));

            String result = service.createSignedUrl(OWN_PUBLIC_URL, 600);

            assertThat(result).isEqualTo(OWN_PUBLIC_URL);
        }

        @Test
        @DisplayName("响应缺少 signedURL 字段时回退原公开 URL")
        void sign_missingField_fallsBackToPublicUrl() {
            when(restTemplate.exchange(
                    anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)
            )).thenReturn(new ResponseEntity<>("{\"unexpected\":1}", HttpStatus.OK));

            String result = service.createSignedUrl(OWN_PUBLIC_URL, 600);

            assertThat(result).isEqualTo(OWN_PUBLIC_URL);
        }

        @Test
        @DisplayName("路径含路径穿越片段时拒绝换签并原样返回")
        void sign_pathTraversal_fallsBackToPublicUrl() {
            String traversal = SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/../secret.png";

            String result = service.createSignedUrl(traversal, 600);

            assertThat(result).isEqualTo(traversal);
            verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class),
                    any(HttpEntity.class), eq(String.class));
        }
    }

    @Nested
    @DisplayName("配置值规范化 + 健康快照（v1.44.0 线上实测缺陷的回归锁定）")
    class NormalizedConfig {

        /**
         * 原缺陷：部署面板粘贴的 SUPABASE_URL 带尾随换行时，
         * isConfigured() 做了 trim 判定「已配置」，而 upload() 用原始字段值，
         * 拼出 https://xxx.supabase.co%0A/storage/v1/... → PUT 直接 DNS 失败。
         * 线上表现：作答附图 100% 失败，健康检查却一直显示 storage UP。
         */
        @Test
        @DisplayName("URL 带尾随换行时，上传地址不得含 %0A（否则 DNS 必失败）")
        void upload_urlWithTrailingNewline_normalized() throws IOException {
            ReflectionTestUtils.setField(service, "supabaseUrl", "https://test.supabase.co\n");
            when(file.getContentType()).thenReturn(MediaType.IMAGE_PNG_VALUE);

            String publicUrl = service.upload(file, "shot.png");

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(url.capture(), any(HttpMethod.class),
                    any(HttpEntity.class), eq(String.class));
            assertThat(url.getValue())
                    .isEqualTo("https://test.supabase.co/storage/v1/object/" + BUCKET + "/shot.png")
                    .doesNotContain("%0A")
                    .doesNotContain("\n")
                    .doesNotContain(" ");
            assertThat(publicUrl)
                    .isEqualTo("https://test.supabase.co/storage/v1/object/public/" + BUCKET + "/shot.png");
        }

        @Test
        @DisplayName("URL 前后有空格与结尾多余 / 时，同样拼出干净地址")
        void upload_urlWithSpacesAndTrailingSlash_normalized() throws IOException {
            ReflectionTestUtils.setField(service, "supabaseUrl", "  https://test.supabase.co/  ");

            service.upload(file, "resume.pdf");

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(url.capture(), any(HttpMethod.class),
                    any(HttpEntity.class), eq(String.class));
            assertThat(url.getValue())
                    .isEqualTo("https://test.supabase.co/storage/v1/object/" + BUCKET + "/resume.pdf")
                    .doesNotContain("//storage");
        }

        @Test
        @DisplayName("service key 带尾随换行时，Authorization 头被清洗（HTTP 头不允许换行）")
        void upload_serviceKeyWithTrailingWhitespace_trimmed() throws IOException {
            ReflectionTestUtils.setField(service, "serviceKey", SERVICE_KEY + "\n");

            service.upload(file, "resume.pdf");

            ArgumentCaptor<HttpEntity<?>> entity = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), any(HttpMethod.class),
                    entity.capture(), eq(String.class));
            String auth = entity.getValue().getHeaders().getFirst("Authorization");
            assertThat(auth).isEqualTo("Bearer " + SERVICE_KEY);
        }

        @Test
        @DisplayName("带尾随空白的配置仍被判定为「已配置」——判定口径与使用口径一致")
        void isConfigured_trailingWhitespace_true() {
            ReflectionTestUtils.setField(service, "supabaseUrl", "https://test.supabase.co\n");
            ReflectionTestUtils.setField(service, "serviceKey", SERVICE_KEY + " ");

            assertThat(service.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("健康快照：最近一次上传失败时必须为 DEGRADED，不能报 UP")
        void healthSnapshot_afterFailure_degraded() {
            ReflectionTestUtils.setField(service, "lastError", "无法连接存储服务（https://x.supabase.co）：DNS");

            java.util.Map<String, Object> snap = service.healthSnapshot();

            assertThat(snap.get("status")).isEqualTo("DEGRADED");
            assertThat(snap.get("configured")).isEqualTo(true);
            assertThat(snap.get("lastError")).isNotNull();
        }

        @Test
        @DisplayName("健康快照：配置正常且从未失败时为 UP")
        void healthSnapshot_ok_up() {
            assertThat(service.healthSnapshot().get("status")).isEqualTo("UP");
        }

        @Test
        @DisplayName("健康快照：未配置时为 DOWN")
        void healthSnapshot_notConfigured_down() {
            ReflectionTestUtils.setField(service, "supabaseUrl", "https://your-project.supabase.co");

            java.util.Map<String, Object> snap = service.healthSnapshot();

            assertThat(snap.get("status")).isEqualTo("DOWN");
            assertThat(snap.get("configured")).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("失败类型区分（P3-07：别对永久性配置故障提示「请稍后重试」）")
    class FailureKindTest {

        @Test
        @DisplayName("连接失败（DNS/TLS）判为 CONFIG_INVALID —— 重试无用")
        void connectionFailure_isConfigInvalid() {
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException(
                            "I/O error on PUT request: unknown host"));

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(service.getLastFailureKind())
                    .isEqualTo(SupabaseStorageService.FailureKind.CONFIG_INVALID);
            assertThat(service.getLastError()).contains("无法连接存储服务");
        }

        @Test
        @DisplayName("上游 5xx 判为 UPSTREAM —— 可以重试")
        void upstreamError_isUpstream() {
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("boom", HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(service.getLastFailureKind())
                    .isEqualTo(SupabaseStorageService.FailureKind.UPSTREAM);
        }

        @Test
        @DisplayName("未配置判为 NOT_CONFIGURED")
        void notConfigured() {
            ReflectionTestUtils.setField(service, "supabaseUrl", "https://your-project.supabase.co");

            assertThatThrownBy(() -> service.upload(file, "resume.pdf"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(service.getLastFailureKind())
                    .isEqualTo(SupabaseStorageService.FailureKind.NOT_CONFIGURED);
        }

        @Test
        @DisplayName("从未失败时为 NONE；成功后也会被重置为 NONE")
        void successResetsKind() throws IOException {
            assertThat(service.getLastFailureKind()).isEqualTo(SupabaseStorageService.FailureKind.NONE);

            // 先失败一次
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("dns"));
            assertThatThrownBy(() -> service.upload(file, "resume.pdf"));
            assertThat(service.getLastFailureKind())
                    .isEqualTo(SupabaseStorageService.FailureKind.CONFIG_INVALID);

            // 再成功一次 → 必须复位
            when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));
            service.upload(file, "resume.pdf");

            assertThat(service.getLastFailureKind()).isEqualTo(SupabaseStorageService.FailureKind.NONE);
            assertThat(service.getLastError()).isNull();
        }

        @Test
        @DisplayName("健康快照在失败时带上 lastFailureKind")
        void healthSnapshotCarriesKind() {
            ReflectionTestUtils.setField(service, "lastError", "无法连接存储服务（https://x）：dns");
            ReflectionTestUtils.setField(service, "lastFailureKind",
                    SupabaseStorageService.FailureKind.CONFIG_INVALID);

            java.util.Map<String, Object> snap = service.healthSnapshot();

            assertThat(snap.get("status")).isEqualTo("DEGRADED");
            assertThat(snap.get("lastFailureKind")).isEqualTo("CONFIG_INVALID");
        }
    }
}
