package com.example.interview.service;

import com.example.interview.common.ConflictException;
import com.example.interview.entity.DataImportLogEntity;
import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.entity.StoryBankEntity;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 个人数据导出 / 导入服务（第三批 C）。
 *
 * <h2>导出</h2>
 * 把用户的 8 类数据组装成单 JSON；**严禁**包含密码哈希 / 盐 / token（只导出可公开的 profile）。
 *
 * <h2>导入</h2>
 * <ul>
 *   <li><b>dryRun 零写库</b>：预览阶段只读，绝不落库；</li>
 *   <li><b>数据指纹冲突保护</b>：{@code replace} 模式下若 {@code expectedFingerprint} 与当前不符且
 *       未 {@code force}，抛 {@link ConflictException}（→ HTTP 409）；{@code merge} 模式仅告警；</li>
 *   <li><b>幂等</b>：{@code apply} 阶段以 {@code (userId, importId)} 为键写入 {@code data_import_log}，
 *       重复提交同 importId 直接返回上次结果，不二次落库（持久表，跨重启有效）；</li>
 *   <li><b>原子性</b>：{@code apply} 在单事务内完成「删除（replace）+ 写入 + 幂等日志」，
 *       任一步失败整体回滚，数据零变化。</li>
 * </ul>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** 备份格式版本（本轮 v3） */
    public static final String SCHEMA_VERSION = "3";

    @Autowired private ResumeRepository resumeRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private InterviewQuestionRepository questionRepository;
    @Autowired private JobApplicationRepository applicationRepository;
    @Autowired private InterviewEventRepository eventRepository;
    @Autowired private FavoriteQuestionRepository favoriteQuestionRepository;
    @Autowired private StoryBankRepository storyBankRepository;
    @Autowired private JobFavoriteRepository jobFavoriteRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataImportLogRepository importLogRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate writeTemplate;

    @PostConstruct
    void initWriteTemplate() {
        writeTemplate = new TransactionTemplate(transactionManager);
    }

    /** 惰性获取写事务模板（切片测试未走 Spring 生命周期时兜底）。 */
    private TransactionTemplate writeTemplate() {
        TransactionTemplate t = writeTemplate;
        if (t == null) {
            synchronized (this) {
                t = writeTemplate;
                if (t == null) {
                    t = new TransactionTemplate(transactionManager);
                    writeTemplate = t;
                }
            }
        }
        return t;
    }

    // ─────────────────────────────── 导出 ───────────────────────────────

    /**
     * 导出用户全部个人数据。
     *
     * @param userId               当前用户 ID
     * @param includeConversations 是否包含智能体会话（默认 false）
     * @return {@code {schemaVersion, exportedAt, dataFingerprint, counts, data}}
     */
    public Map<String, Object> export(String userId, boolean includeConversations) {
        Bundle bundle = loadBundle(userId);

        long questionCount = 0;
        List<Map<String, Object>> sessionViews = new ArrayList<>();
        for (InterviewSessionEntity s : bundle.sessions) {
            List<InterviewQuestionEntity> questions =
                    questionRepository.findBySessionIdOrderByIdAsc(s.getSessionId());
            questionCount += questions.size();
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("id", s.getId());
            sv.put("sessionId", s.getSessionId());
            sv.put("jobDescription", s.getJobDescription());
            sv.put("status", s.getStatus());
            sv.put("resumeId", s.getResumeId());
            sv.put("createdAt", s.getCreatedAt());
            // questions 实体自带 evalDetail 字段（旧数据为 null，不填 0）
            sv.put("questions", questions);
            sessionViews.add(sv);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profilePublic(userId));
        data.put("resumes", bundle.resumes);
        data.put("sessions", sessionViews);
        data.put("applications", bundle.applications);
        data.put("events", bundle.events);
        data.put("favoriteQuestions", bundle.favoriteQuestions);
        data.put("storyBank", bundle.storyBank);
        data.put("jobFavorites", bundle.jobFavorites);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("resumes", bundle.resumes.size());
        counts.put("sessions", bundle.sessions.size());
        counts.put("questions", questionCount);
        counts.put("applications", bundle.applications.size());
        counts.put("events", bundle.events.size());
        counts.put("favoriteQuestions", bundle.favoriteQuestions.size());
        counts.put("storyBank", bundle.storyBank.size());
        counts.put("jobFavorites", bundle.jobFavorites.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("exportedAt", LocalDateTime.now().toString());
        result.put("includeConversations", includeConversations);
        result.put("dataFingerprint", fingerprintOf(bundle));
        result.put("counts", counts);
        result.put("data", data);
        return result;
    }

    /** 仅导出可公开的 profile 字段，杜绝 password/salt/token/role/banned 泄漏。 */
    private Map<String, Object> profilePublic(String userId) {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            Long id = Long.valueOf(userId);
            userRepository.findById(id).ifPresent(u -> {
                p.put("username", u.getUsername());
                p.put("email", u.getEmail());
            });
        } catch (NumberFormatException e) {
            // userId 非数字（理论上不会）——不导出 profile，不影响其余集合
        }
        return p;
    }

    // ─────────────────────────────── 导入 ───────────────────────────────

    /**
     * 导入备份数据（预览或提交）。
     *
     * @param userId 当前用户 ID
     * @param req    请求体：{@code {mode, dryRun, force, expectedFingerprint, importId, payload}}
     * @return 预览 / 结果结构
     */
    public Map<String, Object> importData(String userId, Map<String, Object> req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String mode = normalizeMode(req.get("mode"));
        boolean dryRun = Boolean.TRUE.equals(req.get("dryRun"));
        boolean force = Boolean.TRUE.equals(req.get("force"));
        String expectedFingerprint = trimToNull(str(req.get("expectedFingerprint")));
        String importId = trimToNull(str(req.get("importId")));

        Object payloadObj = req.get("payload");
        if (!(payloadObj instanceof Map<?, ?> payloadRaw)) {
            throw new IllegalArgumentException("payload 缺失或格式错误，应为导出数据对象");
        }
        Map<String, Object> payload = toStringKeyMap(payloadRaw);
        Map<String, Object> data = extractData(payload);

        // 幂等（仅 apply）：同 importId 已提交过则直接返回上次结果
        if (!dryRun) {
            if (importId == null) {
                throw new IllegalArgumentException("importId 不能为空（用于导入幂等）");
            }
            Optional<DataImportLogEntity> prev =
                    importLogRepository.findByUserIdAndImportId(userId, importId);
            if (prev.isPresent()) {
                Map<String, Object> replay = new LinkedHashMap<>();
                replay.put("applied", true);
                replay.put("idempotent", true);
                replay.put("importId", importId);
                replay.put("mode", prev.get().getMode());
                replay.put("summary", parseJsonOrNull(prev.get().getSummaryJson()));
                return replay;
            }
        }

        String currentFingerprint = fingerprintOf(loadBundle(userId));

        boolean fingerprintMismatch =
                expectedFingerprint != null && !expectedFingerprint.equals(currentFingerprint);
        // 冲突：replace + 指纹不符 + 未 force。
        // 【裁决1】仅 apply（dryRun=false）触发 409；**dryRun 永不 409**——否则「导入前必须先 dryRun 预览」
        // 的交互（PRD US-C2 AC1）整体作废。dryRun 下冲突以 canApply=false + warnings 表达。
        boolean conflict = "replace".equals(mode) && fingerprintMismatch && !force;
        if (!dryRun && conflict) {
            throw new ConflictException(
                    "数据在导出后已发生变化（指纹不符），覆盖模式需勾选确认（force）后再导入");
        }

        Plan plan = planImport(userId, mode, data);

        if (fingerprintMismatch) {
            if ("merge".equals(mode)) {
                warn(plan, "global", "-", "检测到你的数据在导出后已变化（指纹不符），合并模式只新增不覆盖");
            } else if ("replace".equals(mode)) {
                warn(plan, "global", "-",
                        "检测到你的数据在导出后已变化（指纹不符），覆盖模式需勾选确认（force）后才会执行");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("dryRun", dryRun);
        result.put("canApply", plan.fatalErrors.isEmpty() && !conflict);
        result.put("currentFingerprint", currentFingerprint);
        result.put("summary", plan.summary);
        result.put("warnings", plan.warnings);
        result.put("fatalErrors", plan.fatalErrors);

        if (dryRun) {
            result.put("applied", false);
            return result;
        }
        if (!plan.fatalErrors.isEmpty()) {
            result.put("applied", false);
            result.put("message", "存在致命错误，已取消导入，数据未发生任何变化");
            return result;
        }

        // 单事务原子写入：删除（replace）+ 写入 + 幂等日志
        writeTemplate().executeWithoutResult(status -> {
            applyPlan(userId, mode, plan);
            DataImportLogEntity logEntry = DataImportLogEntity.builder()
                    .userId(userId)
                    .importId(importId)
                    .mode(mode)
                    .summaryJson(toJson(plan.summary))
                    .build();
            importLogRepository.save(logEntry);
        });

        result.put("applied", true);
        result.put("importId", importId);
        result.put("appliedCounts", plan.summary);
        return result;
    }

    // ─────────────────────────── 计划（零写库） ───────────────────────────

    /** 计算导入计划（纯只读分类，不落库）。 */
    private Plan planImport(String userId, String mode, Map<String, Object> data) {
        Plan plan = new Plan();
        boolean replace = "replace".equals(mode);
        Bundle existing = loadBundle(userId);

        // ---- resumes：键 = targetJob + content ----
        Set<String> resumeKeys = new HashSet<>();
        for (ResumeEntity r : existing.resumes) {
            resumeKeys.add(key2(r.getTargetJob(), r.getContent()));
        }
        List<Map<String, Object>> resumeWrite = new ArrayList<>();
        List<Map<String, Object>> resumeItems = itemList(data.get("resumes"));
        for (int i = 0; i < resumeItems.size(); i++) {
            Map<String, Object> m = resumeItems.get(i);
            String k = key2(str(m.get("targetJob")), str(m.get("content")));
            if (!replace && resumeKeys.contains(k)) {
                bump(plan, "resumes", "skipped");
                continue;
            }
            resumeWrite.add(m);
            bump(plan, "resumes", "added");
        }
        plan.toWrite.put("resumes", resumeWrite);
        plan.payloadResumes = resumeItems;

        // ---- sessions（含嵌套 questions）：键 = sessionId ----
        Set<String> sessionKeys = new HashSet<>();
        for (InterviewSessionEntity s : existing.sessions) {
            sessionKeys.add(nz(s.getSessionId()));
        }
        // 备份文件内所有简历的旧 id 集合（用于判定会话引用的简历是否在文件内）
        Set<Long> payloadResumeIds = new HashSet<>();
        for (Map<String, Object> rm : resumeItems) {
            Long rid = asLong(rm.get("id"));
            if (rid != null) {
                payloadResumeIds.add(rid);
            }
        }
        List<Map<String, Object>> sessionWrite = new ArrayList<>();
        List<Map<String, Object>> sessionItems = itemList(data.get("sessions"));
        for (int i = 0; i < sessionItems.size(); i++) {
            Map<String, Object> m = sessionItems.get(i);
            String sid = trimToNull(str(m.get("sessionId")));
            if (sid == null) {
                fatal(plan, "sessions", i, "缺少 sessionId");
                continue;
            }
            if (!replace && sessionKeys.contains(nz(sid))) {
                bump(plan, "sessions", "skipped");
                continue;
            }
            sessionWrite.add(m);
            bump(plan, "sessions", "added");
            // 裁决2：会话引用的简历不在备份文件内时，导入后关联必然无法复原 → 计入 warnings（不静默）
            Long oldResumeId = asLong(m.get("resumeId"));
            if (oldResumeId != null && !payloadResumeIds.contains(oldResumeId)) {
                warn(plan, "sessions", sid,
                        "该会话引用的简历不在备份文件中，导入后其简历关联将被清空");
            }
        }
        plan.toWrite.put("sessions", sessionWrite);

        // ---- applications：键 = companyName + title ----
        Set<String> appKeys = new HashSet<>();
        for (JobApplicationEntity a : existing.applications) {
            appKeys.add(key2(a.getCompanyName(), a.getTitle()));
        }
        List<Map<String, Object>> appWrite = new ArrayList<>();
        List<Map<String, Object>> appItems = itemList(data.get("applications"));
        for (int i = 0; i < appItems.size(); i++) {
            Map<String, Object> m = appItems.get(i);
            String company = trimToNull(str(m.get("companyName")));
            String title = trimToNull(str(m.get("title")));
            if (company == null || title == null) {
                fatal(plan, "applications", i, "缺少公司名称或岗位名称");
                continue;
            }
            String k = key2(company, title);
            if (!replace && appKeys.contains(k)) {
                bump(plan, "applications", "skipped");
                continue;
            }
            if (trimToNull(str(m.get("applyUrl"))) == null) {
                bump(plan, "applications", "warnings");
                warn(plan, "applications", company + "·" + title, "缺岗位链接，仍将导入但不带链接");
            }
            appWrite.add(m);
            bump(plan, "applications", "added");
        }
        plan.toWrite.put("applications", appWrite);

        // ---- events：键 = title + interviewAt ----
        Set<String> eventKeys = new HashSet<>();
        for (InterviewEventEntity e : existing.events) {
            eventKeys.add(key2(e.getTitle(), str(e.getInterviewAt())));
        }
        List<Map<String, Object>> eventWrite = new ArrayList<>();
        List<Map<String, Object>> eventItems = itemList(data.get("events"));
        for (int i = 0; i < eventItems.size(); i++) {
            Map<String, Object> m = eventItems.get(i);
            String title = trimToNull(str(m.get("title")));
            Object at = m.get("interviewAt");
            if (title == null || at == null || str(at).isBlank()) {
                fatal(plan, "events", i, "缺少标题或面试时间");
                continue;
            }
            String k = key2(title, str(at));
            if (!replace && eventKeys.contains(k)) {
                bump(plan, "events", "skipped");
                continue;
            }
            eventWrite.add(m);
            bump(plan, "events", "added");
        }
        plan.toWrite.put("events", eventWrite);

        // ---- favoriteQuestions：键 = question ----
        Set<String> favKeys = new HashSet<>();
        for (FavoriteQuestionEntity f : existing.favoriteQuestions) {
            favKeys.add(nz(f.getQuestion()));
        }
        List<Map<String, Object>> favWrite = new ArrayList<>();
        List<Map<String, Object>> favItems = itemList(data.get("favoriteQuestions"));
        for (int i = 0; i < favItems.size(); i++) {
            Map<String, Object> m = favItems.get(i);
            String question = trimToNull(str(m.get("question")));
            if (question == null) {
                fatal(plan, "favoriteQuestions", i, "缺少题目内容");
                continue;
            }
            if (!replace && favKeys.contains(nz(question))) {
                bump(plan, "favoriteQuestions", "skipped");
                continue;
            }
            favWrite.add(m);
            bump(plan, "favoriteQuestions", "added");
        }
        plan.toWrite.put("favoriteQuestions", favWrite);

        // ---- storyBank：键 = title ----
        Set<String> storyKeys = new HashSet<>();
        for (StoryBankEntity s : existing.storyBank) {
            storyKeys.add(nz(s.getTitle()));
        }
        List<Map<String, Object>> storyWrite = new ArrayList<>();
        List<Map<String, Object>> storyItems = itemList(data.get("storyBank"));
        for (int i = 0; i < storyItems.size(); i++) {
            Map<String, Object> m = storyItems.get(i);
            String title = trimToNull(str(m.get("title")));
            if (title == null) {
                fatal(plan, "storyBank", i, "缺少故事标题");
                continue;
            }
            if (!replace && storyKeys.contains(nz(title))) {
                bump(plan, "storyBank", "skipped");
                continue;
            }
            storyWrite.add(m);
            bump(plan, "storyBank", "added");
        }
        plan.toWrite.put("storyBank", storyWrite);

        // ---- jobFavorites：键 = jobId ----
        Set<String> jobFavKeys = new HashSet<>();
        for (JobFavoriteEntity f : existing.jobFavorites) {
            jobFavKeys.add(str(f.getJobId()));
        }
        List<Map<String, Object>> jobFavWrite = new ArrayList<>();
        List<Map<String, Object>> jobFavItems = itemList(data.get("jobFavorites"));
        for (int i = 0; i < jobFavItems.size(); i++) {
            Map<String, Object> m = jobFavItems.get(i);
            Object jobId = m.get("jobId");
            if (jobId == null || str(jobId).isBlank()) {
                fatal(plan, "jobFavorites", i, "缺少 jobId");
                continue;
            }
            if (!replace && jobFavKeys.contains(str(jobId))) {
                bump(plan, "jobFavorites", "skipped");
                continue;
            }
            jobFavWrite.add(m);
            bump(plan, "jobFavorites", "added");
        }
        plan.toWrite.put("jobFavorites", jobFavWrite);

        return plan;
    }

    /** 执行写入（须在事务内调用）。 */
    private void applyPlan(String userId, String mode, Plan plan) {
        if ("replace".equals(mode)) {
            deleteAllForUser(userId);
        }
        writeAll(userId, plan);
    }

    /** replace 模式：删除该用户全部可导入数据（顺序满足外键约束）。 */
    private void deleteAllForUser(String userId) {
        // 先删题目与会话（interview_session.resume_id 引用 resume）
        List<InterviewSessionEntity> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (InterviewSessionEntity s : sessions) {
            questionRepository.deleteBySessionId(s.getSessionId());
        }
        sessionRepository.deleteAll(sessions);
        // 其余集合
        applicationRepository.deleteAll(applicationRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        eventRepository.deleteAll(eventRepository.findByUserIdOrderByInterviewAtAsc(userId));
        favoriteQuestionRepository.deleteAll(
                favoriteQuestionRepository.findByUserIdOrderByCreatedAtDesc(userId));
        storyBankRepository.deleteAll(storyBankRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        jobFavoriteRepository.deleteAll(jobFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId));
        // 最后删简历
        resumeRepository.deleteAll(resumeRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    /** 写入计划中的全部新增项。 */
    private void writeAll(String userId, Plan plan) {
        // ① 写入简历并建立「旧 resumeId → 新 id」映射（裁决2：保证「会话↔简历」关联可复原）
        Map<Long, Long> resumeIdMap = new HashMap<>();
        Map<String, Long> existingResumeKeyToId = new HashMap<>();
        for (ResumeEntity er : resumeRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            existingResumeKeyToId.put(key2(er.getTargetJob(), er.getContent()), er.getId());
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("resumes", List.of())) {
            ResumeEntity e = objectMapper.convertValue(m, ResumeEntity.class);
            e.setId(null);
            e.setUserId(userId);
            ResumeEntity saved = resumeRepository.save(e);
            Long oldId = asLong(m.get("id"));
            if (oldId != null && saved != null && saved.getId() != null) {
                resumeIdMap.put(oldId, saved.getId());
            }
        }
        // merge 下被跳过的简历：按内容键解析到已存在简历的 id，供会话回填（否则会误判为「不在文件内」）
        for (Map<String, Object> m : plan.payloadResumes) {
            Long oldId = asLong(m.get("id"));
            if (oldId == null || resumeIdMap.containsKey(oldId)) {
                continue;
            }
            Long existingId = existingResumeKeyToId.get(
                    key2(str(m.get("targetJob")), str(m.get("content"))));
            if (existingId != null) {
                resumeIdMap.put(oldId, existingId);
            }
        }

        // ② 会话：用映射回填 resume_id；仅当引用的简历既不在文件内、也无对等已存在简历时才置 null
        for (Map<String, Object> m : plan.toWrite.getOrDefault("sessions", List.of())) {
            InterviewSessionEntity s = objectMapper.convertValue(m, InterviewSessionEntity.class);
            s.setId(null);
            s.setUserId(userId);
            Long oldResumeId = asLong(m.get("resumeId"));
            s.setResumeId(oldResumeId == null ? null : resumeIdMap.get(oldResumeId));
            InterviewSessionEntity saved = sessionRepository.save(s);
            for (Map<String, Object> qm : itemList(m.get("questions"))) {
                InterviewQuestionEntity q = objectMapper.convertValue(qm, InterviewQuestionEntity.class);
                q.setId(null);
                q.setVersion(null);
                q.setSessionId(saved.getSessionId());
                questionRepository.save(q);
            }
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("applications", List.of())) {
            JobApplicationEntity e = objectMapper.convertValue(m, JobApplicationEntity.class);
            e.setId(null);
            e.setUserId(userId);
            if (e.getJobId() == null || e.getJobId() == 0L) {
                e.setJobId(syntheticJobId(e.getCompanyName(), e.getTitle()));
            }
            applicationRepository.save(e);
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("events", List.of())) {
            InterviewEventEntity e = objectMapper.convertValue(m, InterviewEventEntity.class);
            e.setId(null);
            e.setUserId(userId);
            if (e.getStatus() == null) {
                e.setStatus("UPCOMING");
            }
            eventRepository.save(e);
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("favoriteQuestions", List.of())) {
            FavoriteQuestionEntity e = objectMapper.convertValue(m, FavoriteQuestionEntity.class);
            e.setId(null);
            e.setUserId(userId);
            favoriteQuestionRepository.save(e);
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("storyBank", List.of())) {
            StoryBankEntity e = objectMapper.convertValue(m, StoryBankEntity.class);
            e.setId(null);
            e.setUserId(userId);
            storyBankRepository.save(e);
        }
        for (Map<String, Object> m : plan.toWrite.getOrDefault("jobFavorites", List.of())) {
            JobFavoriteEntity e = objectMapper.convertValue(m, JobFavoriteEntity.class);
            e.setId(null);
            e.setUserId(userId);
            jobFavoriteRepository.save(e);
        }
    }

    // ─────────────────────────────── 指纹 ───────────────────────────────

    /**
     * 数据指纹：{@code sha256(对每个集合拼接 "<name>:<count>:<max(ts)>;")} 取前 16 位。
     *
     * <p>只用于「导出后数据是否又变了」的冲突告警，<b>非授权/校验和</b>；
     * 算法为后端唯一来源，前端 {@code backup.ts} 仅做同构预校验。
     */
    String fingerprintOf(Bundle b) {
        StringBuilder sb = new StringBuilder();
        sb.append(part("resumes", b.resumes.size(), maxTs(b.resumes.stream().map(ResumeEntity::getCreatedAt))));
        sb.append(part("sessions", b.sessions.size(),
                maxTs(b.sessions.stream().map(InterviewSessionEntity::getCreatedAt))));
        sb.append(part("applications", b.applications.size(),
                maxTs(b.applications.stream().map(JobApplicationEntity::getUpdatedAt))));
        sb.append(part("events", b.events.size(), maxTs(b.events.stream().map(InterviewEventEntity::getCreatedAt))));
        sb.append(part("favoriteQuestions", b.favoriteQuestions.size(),
                maxTs(b.favoriteQuestions.stream().map(FavoriteQuestionEntity::getCreatedAt))));
        sb.append(part("storyBank", b.storyBank.size(),
                maxTs(b.storyBank.stream().map(StoryBankEntity::getUpdatedAt))));
        sb.append(part("jobFavorites", b.jobFavorites.size(),
                maxTs(b.jobFavorites.stream().map(JobFavoriteEntity::getCreatedAt))));
        String hex = sha256Hex(sb.toString());
        return hex.length() >= 16 ? hex.substring(0, 16) : hex;
    }

    private static String part(String name, int count, LocalDateTime maxTs) {
        return name + ":" + count + ":" + (maxTs == null ? "-" : maxTs) + ";";
    }

    private static LocalDateTime maxTs(Stream<LocalDateTime> stream) {
        return stream.filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ─────────────────────────────── 装载 ───────────────────────────────

    private Bundle loadBundle(String userId) {
        Bundle b = new Bundle();
        b.resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        b.sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        b.applications = applicationRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        b.events = eventRepository.findByUserIdOrderByInterviewAtAsc(userId);
        b.favoriteQuestions = favoriteQuestionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        b.storyBank = storyBankRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        b.jobFavorites = jobFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return b;
    }

    // ─────────────────────────────── 工具 ───────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> payload) {
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> m) {
            return toStringKeyMap(m);
        }
        return payload;
    }

    private static String normalizeMode(Object v) {
        String s = trimToNull(str(v));
        if (s == null) {
            return "merge";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.equals("merge") && !lower.equals("replace")) {
            throw new IllegalArgumentException("mode 非法，合法取值：merge / replace");
        }
        return lower;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(toStringKeyMap(m));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Map<String, Object> coll(Plan plan, String name) {
        return plan.summary.computeIfAbsent(name, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("added", 0);
            m.put("merged", 0);
            m.put("skipped", 0);
            m.put("warnings", 0);
            return m;
        });
    }

    private void bump(Plan plan, String name, String field) {
        Map<String, Object> m = coll(plan, name);
        m.put(field, ((Number) m.get(field)).intValue() + 1);
    }

    private void warn(Plan plan, String collection, String key, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("collection", collection);
        w.put("key", key);
        w.put("message", message);
        plan.warnings.add(w);
    }

    private void fatal(Plan plan, String collection, int row, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("collection", collection);
        e.put("row", row);
        e.put("message", message);
        plan.fatalErrors.add(e);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化导入汇总失败：{}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("解析幂等日志失败：{}", e.getMessage());
            return null;
        }
    }

    private static String key2(String a, String b) {
        return nz(a) + "\u0000" + nz(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 安全读取 Long（Number 或可解析字符串）；否则 null */
    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 内容派生的稳定负值 jobId（哨兵）——与 {@code ApplicationImportService.syntheticJobId} 同算法。
     *
     * <p><b>为何用负值而非 0（裁决3）</b>：{@code job_application} 上有唯一约束
     * {@code uk_job_application_user_job(user_id, job_id)}；导入的台账行没有真实岗位，
     * 若统一写 {@code jobId=0}，同用户第二条导入即撞唯一约束。用「公司+岗位」派生稳定负值：
     * 同内容恒等（幂等可复现）、异内容不同、且恒为负以便与真实岗位 id（正数）区分。
     */
    static long syntheticJobId(String company, String title) {
        String key = nz(company) + "\u0000" + nz(title);
        long v;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (h[i] & 0xFFL);
            }
            v = v & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            v = Math.abs((long) key.hashCode());
        }
        if (v == 0) {
            v = 1;
        }
        return -v;
    }

    /** 用户数据快照（用于导出与指纹）。 */
    static final class Bundle {
        List<ResumeEntity> resumes = List.of();
        List<InterviewSessionEntity> sessions = List.of();
        List<JobApplicationEntity> applications = List.of();
        List<InterviewEventEntity> events = List.of();
        List<FavoriteQuestionEntity> favoriteQuestions = List.of();
        List<StoryBankEntity> storyBank = List.of();
        List<JobFavoriteEntity> jobFavorites = List.of();
    }

    /** 导入计划。 */
    private static final class Plan {
        final Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
        final List<Map<String, Object>> warnings = new ArrayList<>();
        final List<Map<String, Object>> fatalErrors = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> toWrite = new LinkedHashMap<>();
        /** 备份文件内全部简历（含 merge 下被跳过的），用于 resumeId 重映射解析 */
        List<Map<String, Object>> payloadResumes = new ArrayList<>();
    }
}
