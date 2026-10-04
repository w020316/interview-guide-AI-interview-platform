package com.example.interview.service;

import com.example.interview.common.ConflictException;
import com.example.interview.entity.DataImportLogEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.DataImportLogRepository;
import com.example.interview.repository.FavoriteQuestionRepository;
import com.example.interview.repository.InterviewEventRepository;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.JobApplicationRepository;
import com.example.interview.repository.JobFavoriteRepository;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.repository.StoryBankRepository;
import com.example.interview.repository.UserRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackupService 单元测试（第三批 C · 导出/导入）")
class BackupServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private InterviewQuestionRepository questionRepository;
    @Mock private JobApplicationRepository applicationRepository;
    @Mock private InterviewEventRepository eventRepository;
    @Mock private FavoriteQuestionRepository favoriteQuestionRepository;
    @Mock private StoryBankRepository storyBankRepository;
    @Mock private JobFavoriteRepository jobFavoriteRepository;
    @Mock private UserRepository userRepository;
    @Mock private DataImportLogRepository importLogRepository;
    @Mock private PlatformTransactionManager transactionManager;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @InjectMocks private BackupService service;

    @BeforeEach
    void setUp() {
        service.initWriteTemplate();
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        // 默认空数据（各测试可覆盖）
        lenient().when(resumeRepository.findByUserIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(sessionRepository.findByUserIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(applicationRepository.findByUserIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(eventRepository.findByUserIdOrderByInterviewAtAsc(anyString())).thenReturn(List.of());
        lenient().when(favoriteQuestionRepository.findByUserIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(storyBankRepository.findByUserIdOrderByUpdatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(jobFavoriteRepository.findByUserIdOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        lenient().when(importLogRepository.findByUserIdAndImportId(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    private Map<String, Object> payloadWith() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resumes", new ArrayList<>());
        data.put("sessions", new ArrayList<>());
        data.put("applications", new ArrayList<>());
        data.put("events", new ArrayList<>());
        data.put("favoriteQuestions", new ArrayList<>());
        data.put("storyBank", new ArrayList<>());
        data.put("jobFavorites", new ArrayList<>());
        return data;
    }

    private Map<String, Object> importReq(String mode, boolean dryRun, boolean force,
                                          String expectedFp, String importId, Map<String, Object> payload) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("mode", mode);
        req.put("dryRun", dryRun);
        req.put("force", force);
        req.put("expectedFingerprint", expectedFp);
        req.put("importId", importId);
        req.put("payload", payload);
        return req;
    }

    // ─────────────────────────── 导出 ───────────────────────────

    @Test
    @DisplayName("export：返回 v3 结构，profile 不含密码哈希（严禁 password/salt/token）")
    void export_noSecrets() throws Exception {
        UserEntity user = UserEntity.builder().id(1L).username("alice")
                .passwordHash("SECRET_HASH_VALUE").email("a@x.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        ResumeEntity r = ResumeEntity.builder().id(5L).userId("1").content("简历").build();
        when(resumeRepository.findByUserIdOrderByCreatedAtDesc("1")).thenReturn(List.of(r));

        Map<String, Object> result = service.export("1", false);

        assertThat(result.get("schemaVersion")).isEqualTo("3");
        assertThat(result.get("dataFingerprint")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) result.get("counts");
        assertThat(counts.get("resumes")).isEqualTo(1);

        String json = objectMapper.writeValueAsString(result);
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("SECRET_HASH_VALUE");
        assertThat(json).doesNotContain("salt");
        assertThat(json).doesNotContain("token");
        assertThat(json).contains("a@x.com");
    }

    @Test
    @DisplayName("指纹：空数据下稳定可复现（导出与导入重算一致）")
    void fingerprint_deterministicForEmpty() {
        Map<String, Object> export = service.export("1", false);
        String fp1 = (String) export.get("dataFingerprint");
        String fp2 = (String) service.export("1", false).get("dataFingerprint");
        assertThat(fp1).isEqualTo(fp2).hasSize(16);
    }

    // ─────────────────────────── 导入 · dryRun ───────────────────────────

    @Test
    @DisplayName("dryRun：零写库，返回 canApply 与各集合计数（新增/跳过）")
    void dryRun_zeroWrite() {
        ResumeEntity existing = ResumeEntity.builder().id(1L).userId("1")
                .targetJob("Java").content("旧简历").build();
        when(resumeRepository.findByUserIdOrderByCreatedAtDesc("1")).thenReturn(List.of(existing));

        Map<String, Object> payload = payloadWith();
        List<Map<String, Object>> resumes = new ArrayList<>();
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("targetJob", "Java");
        dup.put("content", "旧简历");   // 与已有一致 → 跳过
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("targetJob", "后端");
        fresh.put("content", "新简历");  // 新增
        resumes.add(dup);
        resumes.add(fresh);
        payload.put("resumes", resumes);

        Map<String, Object> result = service.importData("1",
                importReq("merge", true, false, null, null, payload));

        assertThat(result.get("dryRun")).isEqualTo(true);
        assertThat(result.get("canApply")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> resumeSummary = (Map<String, Object>) summary.get("resumes");
        assertThat(resumeSummary.get("added")).isEqualTo(1);
        assertThat(resumeSummary.get("skipped")).isEqualTo(1);

        // dryRun 前后不得写库（原子性/零写）
        verify(resumeRepository, never()).save(any());
        verify(resumeRepository, never()).deleteAll(any());
        verify(importLogRepository, never()).save(any());
    }

    // ─────────────────────────── 导入 · 冲突 409 ───────────────────────────

    @Test
    @DisplayName("裁决1：dryRun + replace + 指纹不符 + 未 force → 【不 409】200 且 canApply=false、零写库")
    void dryRun_replaceFingerprintMismatch_noConflict() {
        Map<String, Object> payload = payloadWith();

        // dryRun=true：即使指纹不符也**绝不**抛 ConflictException，只以 canApply=false + warnings 表达
        Map<String, Object> result = service.importData("1",
                importReq("replace", true, false, "deadbeefdeadbeef", null, payload));

        assertThat(result.get("dryRun")).isEqualTo(true);
        assertThat(result.get("canApply")).isEqualTo(false);
        assertThat(result.get("applied")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        // 必须有警告解释「指纹不符，replace 需 force」
        assertThat(warnings).isNotEmpty();
        assertThat(warnings).extracting(w -> String.valueOf(w.get("message")))
                .anyMatch(m -> m.contains("force"));
        // dryRun 冲突阶段零写库
        verify(resumeRepository, never()).save(any());
        verify(resumeRepository, never()).deleteAll(any());
        verify(importLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("裁决1：apply + replace + 指纹不符 + 未 force → ConflictException（→409）")
    void apply_replaceFingerprintMismatch_conflict() {
        Map<String, Object> payload = payloadWith();
        assertThatThrownBy(() -> service.importData("1",
                importReq("replace", false, false, "deadbeefdeadbeef", "imp-1", payload)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("指纹不符");
        // 冲突阶段零写库
        verify(resumeRepository, never()).save(any());
        verify(importLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("apply + replace + 指纹不符 + force=true → 不 409，正常覆盖")
    void apply_replaceFingerprintMismatch_forceAllowed() {
        Map<String, Object> payload = payloadWith();
        Map<String, Object> result = service.importData("1",
                importReq("replace", false, true, "deadbeefdeadbeef", "imp-force", payload));

        assertThat(result.get("applied")).isEqualTo(true);
        verify(resumeRepository).deleteAll(any());
        verify(importLogRepository).save(any(DataImportLogEntity.class));
    }

    @Test
    @DisplayName("replace + force=true → 允许覆盖（即使指纹不符）")
    void replace_forceAllowed() {
        Map<String, Object> payload = payloadWith();
        List<Map<String, Object>> resumes = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("targetJob", "Java");
        r.put("content", "新简历");
        resumes.add(r);
        payload.put("resumes", resumes);

        Map<String, Object> result = service.importData("1",
                importReq("replace", false, true, "deadbeefdeadbeef", "imp-2", payload));

        assertThat(result.get("applied")).isEqualTo(true);
        // replace 会先删后写
        verify(resumeRepository).deleteAll(any());
        verify(resumeRepository).save(any(ResumeEntity.class));
        verify(importLogRepository).save(any(DataImportLogEntity.class));
    }

    // ─────────────────────────── 导入 · resumeId 重映射（裁决2） ───────────────────────────

    @Test
    @DisplayName("裁决2：导出→导入后「会话↔简历」关联仍可解析（resumeId 重映射为新 id，非 null）")
    void import_remapsResumeIdOnApply() {
        // 备份文件内：一条简历（旧 id=100）+ 一条引用该简历的会话（resumeId=100）
        Map<String, Object> payload = payloadWith();
        List<Map<String, Object>> resumes = new ArrayList<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 100L);
        r.put("targetJob", "Java");
        r.put("content", "简历内容");
        resumes.add(r);
        payload.put("resumes", resumes);

        List<Map<String, Object>> sessions = new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sessionId", "sess-1");
        s.put("jobDescription", "JD");
        s.put("resumeId", 100L);       // 引用的旧简历 id
        s.put("questions", new ArrayList<>());
        sessions.add(s);
        payload.put("sessions", sessions);

        // DB 重新分配主键：简历新 id=999，会话新 id=7
        when(resumeRepository.save(any(ResumeEntity.class))).thenAnswer(inv -> {
            ResumeEntity e = inv.getArgument(0);
            e.setId(999L);
            return e;
        });
        when(sessionRepository.save(any(InterviewSessionEntity.class))).thenAnswer(inv -> {
            InterviewSessionEntity e = inv.getArgument(0);
            e.setId(7L);
            return e;
        });

        Map<String, Object> result = service.importData("1",
                importReq("merge", false, false, null, "imp-remap", payload));

        assertThat(result.get("applied")).isEqualTo(true);
        ArgumentCaptor<InterviewSessionEntity> captor =
                ArgumentCaptor.forClass(InterviewSessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        // 关键断言：会话的 resumeId 被重映射为简历的新 id（999），而不是 null / 旧值 100
        assertThat(captor.getValue().getResumeId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("裁决2：会话语引用的简历不在备份文件内 → 计入 warnings（不静默）")
    void import_sessionResumeNotInPayload_warns() {
        Map<String, Object> payload = payloadWith();
        List<Map<String, Object>> sessions = new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sessionId", "sess-x");
        s.put("resumeId", 888L);   // 备份文件内并无 id=888 的简历
        s.put("questions", new ArrayList<>());
        sessions.add(s);
        payload.put("sessions", sessions);

        Map<String, Object> result = service.importData("1",
                importReq("merge", true, false, null, null, payload));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        assertThat(warnings).extracting(w -> String.valueOf(w.get("message")))
                .anyMatch(m -> m.contains("简历"));
    }

    // ─────────────────────────── 导入 · 幂等 ───────────────────────────

    @Test
    @DisplayName("同 importId 重复提交 → 返回上次结果，不二次导入")
    void import_idempotentReplay() {
        DataImportLogEntity prev = DataImportLogEntity.builder()
                .id(9L).userId("1").importId("imp-loop").mode("merge")
                .summaryJson("{\"resumes\":{\"added\":1}}").build();
        when(importLogRepository.findByUserIdAndImportId("1", "imp-loop")).thenReturn(Optional.of(prev));

        Map<String, Object> result = service.importData("1",
                importReq("merge", false, false, null, "imp-loop", payloadWith()));

        assertThat(result.get("idempotent")).isEqualTo(true);
        assertThat(result.get("importId")).isEqualTo("imp-loop");
        // 不重复写库
        verify(resumeRepository, never()).save(any());
        verify(importLogRepository, never()).save(any());
    }

    // ─────────────────────────── 导入 · 参数 / 致命错误 ───────────────────────────

    @Test
    @DisplayName("apply 缺少 importId → IllegalArgumentException（400）")
    void apply_missingImportId() {
        assertThatThrownBy(() -> service.importData("1",
                importReq("merge", false, false, null, null, payloadWith())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("importId");
    }

    @Test
    @DisplayName("mode 非法 → IllegalArgumentException（400）")
    void invalidMode() {
        assertThatThrownBy(() -> service.importData("1",
                importReq("bad", true, false, null, null, payloadWith())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("payload 缺失 → IllegalArgumentException（400）")
    void missingPayload() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("mode", "merge");
        req.put("dryRun", true);
        assertThatThrownBy(() -> service.importData("1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("存在致命行（投递缺公司）→ canApply=false 且零写库")
    void import_fatalError() {
        Map<String, Object> payload = payloadWith();
        List<Map<String, Object>> apps = new ArrayList<>();
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("title", "缺公司");
        apps.add(bad);
        payload.put("applications", apps);

        Map<String, Object> result = service.importData("1",
                importReq("merge", false, false, null, "imp-3", payload));

        assertThat(result.get("canApply")).isEqualTo(false);
        assertThat(result.get("applied")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fatal = (List<Map<String, Object>>) result.get("fatalErrors");
        assertThat(fatal).isNotEmpty();
        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("merge 模式指纹不符 → 允许并告警（不 409）")
    void merge_fingerprintMismatch_warnOnly() {
        Map<String, Object> result = service.importData("1",
                importReq("merge", true, false, "deadbeefdeadbeef", null, payloadWith()));

        assertThat(result.get("canApply")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        assertThat(warnings).extracting(w -> w.get("collection")).contains("global");
    }

    @Test
    @DisplayName("合成 jobId：稳定且不同内容不碰撞")
    void syntheticJobId() {
        long a = BackupService.syntheticJobId("字节", "Java");
        long b = BackupService.syntheticJobId("字节", "Java");
        long c = BackupService.syntheticJobId("腾讯", "Java");
        assertThat(a).isEqualTo(b).isNegative();
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("指纹：随数据量变化而变化")
    void fingerprint_changesWithData() {
        String empty = (String) service.export("1", false).get("dataFingerprint");
        ResumeEntity r = ResumeEntity.builder().id(1L).userId("1").content("x")
                .createdAt(LocalDateTime.now()).build();
        when(resumeRepository.findByUserIdOrderByCreatedAtDesc("1")).thenReturn(List.of(r));
        String withData = (String) service.export("1", false).get("dataFingerprint");
        assertThat(withData).isNotEqualTo(empty);
    }
}
