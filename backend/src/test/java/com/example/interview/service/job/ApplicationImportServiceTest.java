package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.repository.JobApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationImportService 单元测试（第三批 F · 原子导入）")
class ApplicationImportServiceTest {

    @Mock private JobApplicationRepository repository;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private ApplicationImportService service;

    @BeforeEach
    void setUp() {
        service.initWriteTemplate();
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
    }

    private Map<String, Object> row(String company, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companyName", company);
        m.put("title", title);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        return (Map<String, Object>) result.get("summary");
    }

    @Test
    @DisplayName("dryRun：零写库，四类分类正确（新增/跳过/错误）")
    void dryRun_zeroWriteAndClassification() {
        JobApplicationEntity existing = JobApplicationEntity.builder()
                .id(1L).userId("u1").jobId(1L)
                .companyName("美团").title("后端").status("PLANNED").build();
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("字节跳动", "Java 后端"));        // ADD
        rows.add(row("美团", "后端"));                 // SKIP（已在台账）
        rows.add(row("腾讯", "前端"));                 // ADD
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("title", "缺公司");
        rows.add(bad);                                 // ERROR

        Map<String, Object> result = service.run("u1", "csv", rows, true);

        assertThat(result.get("dryRun")).isEqualTo(true);
        assertThat(result.get("canApply")).isEqualTo(false);
        Map<String, Object> s = summary(result);
        assertThat(s.get("added")).isEqualTo(2);
        assertThat(s.get("skipped")).isEqualTo(1);
        assertThat(s.get("errors")).isEqualTo(1);

        // dryRun 必须零写库
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("dryRun：去重键大小写/空格不敏感")
    void dryRun_dedupeNormalization() {
        JobApplicationEntity existing = JobApplicationEntity.builder()
                .id(1L).userId("u1").jobId(1L)
                .companyName("ByteDance").title("Java").status("PLANNED").build();
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(" bytedance ", " java "));  // 归一化后与已有一致 → SKIP

        Map<String, Object> result = service.run("u1", "csv", rows, true);

        assertThat(summary(result).get("skipped")).isEqualTo(1);
        assertThat(summary(result).get("added")).isEqualTo(0);
    }

    @Test
    @DisplayName("dryRun：批次内重复行只计一次新增，其余跳过")
    void dryRun_batchDuplicate() {
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("字节", "Java"));
        rows.add(row("字节", "Java"));

        Map<String, Object> result = service.run("u1", "paste", rows, true);

        assertThat(summary(result).get("added")).isEqualTo(1);
        assertThat(summary(result).get("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("apply：单事务原子写入，新增行落库；致命错误则不写")
    void apply_writesNewRows() {
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("字节跳动", "Java 后端"));
        rows.add(row("腾讯", "前端"));

        Map<String, Object> result = service.run("u1", "csv", rows, false);

        assertThat(result.get("applied")).isEqualTo(true);
        ArgumentCaptor<JobApplicationEntity> captor = ArgumentCaptor.forClass(JobApplicationEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JobApplicationEntity::getCompanyName)
                .containsExactlyInAnyOrder("字节跳动", "腾讯");
        assertThat(captor.getAllValues().get(0).getJobId()).isNegative();
    }

    @Test
    @DisplayName("apply：存在致命错误行 → applied=false，message 明确，且零写库")
    void apply_fatalError_zeroWrite() {
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of());

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("字节跳动", "Java 后端"));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("companyName", "腾讯"); // 缺 title
        rows.add(bad);

        Map<String, Object> result = service.run("u1", "csv", rows, false);

        assertThat(result.get("canApply")).isEqualTo(false);
        assertThat(result.get("applied")).isEqualTo(false);
        assertThat(result.get("message").toString()).contains("未写入任何数据");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("MERGE：仅补缺失字段，不覆盖已有值（R7）")
    void apply_mergeFillsOnlyMissingFields() {
        JobApplicationEntity existing = JobApplicationEntity.builder()
                .id(1L).userId("u1").jobId(1L)
                .companyName("字节").title("Java")
                .location("北京")           // 已有，不得覆盖
                .salary(null)               // 缺失，可补
                .applyUrl(null)             // 缺失，可补
                .status("PLANNED").build();
        when(repository.findByUserIdOrderByUpdatedAtDesc("u1")).thenReturn(List.of(existing));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> m = row("字节", "Java");
        m.put("location", "上海");      // 不应覆盖
        m.put("salary", "30k");
        m.put("applyUrl", "https://x");
        rows.add(m);

        Map<String, Object> result = service.run("u1", "csv", rows, true);

        assertThat(summary(result).get("merged")).isEqualTo(1);
        // dryRun 不落库、不改动实体
        verify(repository, never()).save(any());
        assertThat(existing.getLocation()).isEqualTo("北京");
        assertThat(existing.getSalary()).isNull();
    }

    @Test
    @DisplayName("状态/日期归一：非法状态回落 PLANNED，多格式日期解析")
    void normalization_statusAndDate() {
        assertThat(ApplicationImportService.parseStatus("applied")).isEqualTo("APPLIED");
        assertThat(ApplicationImportService.parseStatus("BAD")).isEqualTo("PLANNED");
        assertThat(ApplicationImportService.parseStatus(null)).isEqualTo("PLANNED");
        assertThat(ApplicationImportService.parseDate("2026-10-08")).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(ApplicationImportService.parseDate("2026/10/8")).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(ApplicationImportService.parseDate("不是日期")).isNull();
        assertThat(ApplicationImportService.parseDate("")).isNull();
    }

    @Test
    @DisplayName("去重键归一化：去空格 + 大小写不敏感")
    void dedupeKey_normalization() {
        assertThat(ApplicationImportService.dedupeKey(" Byte ", "Java"))
                .isEqualTo(ApplicationImportService.dedupeKey("byte", " java "));
    }

    @Test
    @DisplayName("合成 jobId：稳定且不同内容不碰撞")
    void syntheticJobId_stableAndDistinct() {
        long a1 = ApplicationImportService.syntheticJobId("字节", "Java");
        long a2 = ApplicationImportService.syntheticJobId("字节", "Java");
        long b = ApplicationImportService.syntheticJobId("腾讯", "Java");
        assertThat(a1).isEqualTo(a2).isNegative();
        assertThat(a1).isNotEqualTo(b);
    }
}
