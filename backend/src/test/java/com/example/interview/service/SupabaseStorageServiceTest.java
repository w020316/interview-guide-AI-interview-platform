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
}
