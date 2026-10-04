package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.repository.JobApplicationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 投递台账批量导入服务（第三批 F）。
 *
 * <p>把群表格（CSV/TSV/粘贴文本解析后的行）整份导入「我的台账」：
 * <ul>
 *   <li><b>表头无关的行归一化</b>：列名由前端映射，后端只吃结构化行；</li>
 *   <li><b>去重键</b> {@code (userId, companyName, title)} 归一化（去空格 + 大小写不敏感）；</li>
 *   <li><b>四类分类</b>：ADD 新增 / MERGE 合并（仅补缺失字段，绝不覆盖已有值）/ SKIP 跳过 / ERROR 致命；</li>
 *   <li><b>dryRun 零写库</b>：预览阶段绝不落库；</li>
 *   <li><b>单事务原子写入</b>：apply 阶段任一步失败整体回滚，数据零变化。</li>
 * </ul>
 *
 * <p><b>合规边界（R1）</b>：导入的行只进本地台账，<b>不触发任何投递动作</b>；
 * UI 需保留「导入不代替你投递」的语义锚点。
 */
@Service
public class ApplicationImportService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationImportService.class);

    /** 单次导入行数上限（PRD F：≤200 行） */
    public static final int MAX_ROWS = 200;

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日")
    };

    @Autowired
    private JobApplicationRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /**
     * 执行导入（预览或提交）。
     *
     * @param userId 当前用户 ID
     * @param source 来源（csv/tsv/paste，仅回显）
     * @param rows   归一化前的行（每行一个 Map）
     * @param dryRun true=仅预览零写库；false=单事务原子写入
     * @return 预览/结果结构（见 {@link #render}）
     */
    public Map<String, Object> run(String userId, String source,
                                   List<Map<String, Object>> rows, boolean dryRun) {
        if (dryRun) {
            return render(buildPlan(userId, rows, false), source, true);
        }
        // apply 前先只读预检：存在致命行则直接拒绝，保证零写库
        Plan pre = buildPlan(userId, rows, false);
        if (!pre.canApply()) {
            Map<String, Object> rejected = render(pre, source, false);
            rejected.put("applied", false);
            rejected.put("message", "存在致命错误行（缺少公司名称/岗位名称），已取消导入，未写入任何数据");
            return rejected;
        }
        // 单事务原子写入
        Plan applied = writeTemplate().execute(status -> buildPlan(userId, rows, true));
        Map<String, Object> result = render(applied, source, false);
        result.put("applied", true);
        return result;
    }

    /** 组装响应结构。 */
    private Map<String, Object> render(Plan plan, String source, boolean dryRun) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", plan.added);
        summary.put("merged", plan.merged);
        summary.put("skipped", plan.skipped);
        summary.put("errors", plan.errors);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("dryRun", dryRun);
        result.put("canApply", plan.canApply());
        result.put("summary", summary);
        result.put("rows", plan.rows);
        result.put("fatalErrors", plan.fatalErrors);
        return result;
    }

    /**
     * 分类并（可选）写入。
     *
     * @param persist true 时执行写库（仅在事务内调用）；false 时纯只读（dryRun/预检）
     */
    private Plan buildPlan(String userId, List<Map<String, Object>> rows, boolean persist) {
        Plan plan = new Plan();
        Map<String, JobApplicationEntity> existingByKey = new HashMap<>();
        for (JobApplicationEntity a : repository.findByUserIdOrderByUpdatedAtDesc(userId)) {
            existingByKey.putIfAbsent(dedupeKey(a.getCompanyName(), a.getTitle()), a);
        }
        Set<String> seenInBatch = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> raw = rows.get(i) == null ? Map.of() : rows.get(i);
            String company = trimToNull(str(raw.get("companyName")));
            String title = trimToNull(str(raw.get("title")));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("normalized", normalized(raw, company, title));

            if (company == null || title == null) {
                String reason = company == null ? "缺少公司名称" : "缺少岗位名称";
                plan.errors++;
                row.put("action", "ERROR");
                row.put("reason", reason);
                plan.rows.add(row);
                Map<String, Object> fatal = new LinkedHashMap<>();
                fatal.put("index", i);
                fatal.put("message", reason);
                plan.fatalErrors.add(fatal);
                continue;
            }

            String key = dedupeKey(company, title);
            JobApplicationEntity existing = existingByKey.get(key);
            if (existing != null) {
                Map<String, Object> patch = missingPatch(existing, raw);
                if (!patch.isEmpty()) {
                    plan.merged++;
                    row.put("action", "MERGE");
                    row.put("reason", "台账已存在（公司+岗位），仅补充缺失字段：" + String.join("/", patch.keySet()));
                    if (persist) {
                        applyPatch(existing, patch);
                        repository.save(existing);
                    }
                } else {
                    plan.skipped++;
                    row.put("action", "SKIP");
                    row.put("reason", "台账已存在该投递（公司+岗位）");
                }
                plan.rows.add(row);
                continue;
            }

            if (seenInBatch.contains(key)) {
                plan.skipped++;
                row.put("action", "SKIP");
                row.put("reason", "本次导入内存在重复行（公司+岗位）");
                plan.rows.add(row);
                continue;
            }

            seenInBatch.add(key);
            plan.added++;
            row.put("action", "ADD");
            row.put("reason", "将新增到台账");
            plan.rows.add(row);
            if (persist) {
                repository.save(buildEntity(userId, company, title, raw));
            }
        }
        return plan;
    }

    /**
     * 构造新台账记录（平台标记为「导入」）。
     *
     * <p><b>jobId 为何用「内容派生的合成负值哨兵」（裁决3）</b>：
     * 导入行是用户手动粘贴的群表格，<b>本就没有真实岗位</b>。而 {@code job_application} 上有
     * 唯一约束 {@code uk_job_application_user_job(user_id, job_id)}——若把所有导入行统统一写
     * {@code jobId=0}，同一用户导入第二条起就会**直接撞唯一约束**导致整批导入失败。
     * 故这里以「公司+岗位」内容派生一个稳定的负值作为哨兵：同一内容恒定得同一 id（幂等可复现），
     * 不同内容得不同 id（互不冲突），且<b>永远为负</b>以便与真实岗位 id（正数）区分。
     *
     * <p><b>前端契约</b>：台账行若 {@code jobId < 0}（或 {@code jobId <= 0}）即代表「该行无关联岗位」，
     * 前端<b>不得</b>渲染跳转到 {@code /jobs/{id}} 的链接/按钮；应改为展示岗位名文本或「自定义导入」标记。
     */
    private JobApplicationEntity buildEntity(String userId, String company, String title,
                                             Map<String, Object> raw) {
        return JobApplicationEntity.builder()
                .userId(userId)
                .jobId(syntheticJobId(company, title))
                .title(title)
                .companyName(company)
                .platform("导入")
                .location(trimToNull(str(raw.get("location"))))
                .salary(trimToNull(str(raw.get("salary"))))
                .deadline(parseDate(raw.get("deadline")))
                .applyUrl(trimToNull(str(raw.get("applyUrl"))))
                .status(parseStatus(raw.get("status")))
                .note(trimToNull(str(raw.get("note"))))
                .build();
    }

    /** 预览用归一化结构（不落库）。 */
    private Map<String, Object> normalized(Map<String, Object> raw, String company, String title) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("companyName", company);
        n.put("title", title);
        n.put("location", trimToNull(str(raw.get("location"))));
        n.put("salary", trimToNull(str(raw.get("salary"))));
        n.put("deadline", parseDate(raw.get("deadline")));
        n.put("applyUrl", trimToNull(str(raw.get("applyUrl"))));
        n.put("status", parseStatus(raw.get("status")));
        n.put("note", trimToNull(str(raw.get("note"))));
        return n;
    }

    /** 计算「仅补缺失」的补丁：已有字段非空则不覆盖（R7 不破坏既有数据）。 */
    private Map<String, Object> missingPatch(JobApplicationEntity e, Map<String, Object> raw) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (isBlank(e.getLocation())) {
            putIfPresent(patch, "location", trimToNull(str(raw.get("location"))));
        }
        if (isBlank(e.getSalary())) {
            putIfPresent(patch, "salary", trimToNull(str(raw.get("salary"))));
        }
        if (e.getDeadline() == null) {
            LocalDate d = parseDate(raw.get("deadline"));
            if (d != null) {
                patch.put("deadline", d);
            }
        }
        if (isBlank(e.getApplyUrl())) {
            putIfPresent(patch, "applyUrl", trimToNull(str(raw.get("applyUrl"))));
        }
        if (isBlank(e.getNote())) {
            putIfPresent(patch, "note", trimToNull(str(raw.get("note"))));
        }
        return patch;
    }

    private void applyPatch(JobApplicationEntity e, Map<String, Object> patch) {
        if (patch.containsKey("location")) {
            e.setLocation((String) patch.get("location"));
        }
        if (patch.containsKey("salary")) {
            e.setSalary((String) patch.get("salary"));
        }
        if (patch.containsKey("deadline")) {
            e.setDeadline((LocalDate) patch.get("deadline"));
        }
        if (patch.containsKey("applyUrl")) {
            e.setApplyUrl((String) patch.get("applyUrl"));
        }
        if (patch.containsKey("note")) {
            e.setNote((String) patch.get("note"));
        }
    }

    /** 去重键：公司+岗位，去空格、大小写不敏感。 */
    static String dedupeKey(String company, String title) {
        return normKey(company) + "\u0000" + normKey(title);
    }

    private static String normKey(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 状态归一：合法则大写，非法/缺失回落到 PLANNED（非致命）。 */
    static String parseStatus(Object v) {
        String s = trimToNull(str(v));
        if (s == null) {
            return JobApplicationEntity.STATUS_PLANNED;
        }
        String upper = s.toUpperCase(Locale.ROOT);
        return JobApplicationService.ALL_STATUSES.contains(upper)
                ? upper : JobApplicationEntity.STATUS_PLANNED;
    }

    /** 日期解析：支持 ISO 与常见中文/斜杠/点分格式；不可解析返回 null（非致命）。 */
    static LocalDate parseDate(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    /**
     * 内容派生的稳定负值 jobId（哨兵）。
     *
     * <p><b>确定性</b>：{@code sha256(归一化(公司)+"\0"+归一化(岗位))} 取前 8 字节 → 恒非负 → 取负；
     * 因此「同内容必得同 id」（重复导入/重放幂等），「不同内容得不同 id」（不互相覆盖）。
     *
     * <p><b>为什么必须是负值</b>：{@code job_application} 的唯一约束
     * {@code uk_job_application_user_job(user_id, job_id)} 要求同一用户的 jobId 唯一；导入行无真实岗位，
     * 若统一写 {@code 0} 则第二条即冲突。负值既满足唯一性，又能让前端/后端一眼识别「合成岗位」。
     */
    static long syntheticJobId(String company, String title) {
        String key = normKey(company) + "\u0000" + normKey(title);
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 导入计划（分类结果 + 致命错误）。 */
    private static final class Plan {
        int added;
        int merged;
        int skipped;
        int errors;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<Map<String, Object>> fatalErrors = new ArrayList<>();

        boolean canApply() {
            return errors == 0;
        }
    }
}
