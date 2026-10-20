package com.example.interview.service.resume;

import com.example.interview.common.BusinessException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.ResumeDocumentEntity;
import com.example.interview.repository.ResumeDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 结构化简历文档业务服务（v1.67.0）
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>只存与取</b>——不在服务端做渲染，渲染完全交给前端模板组件
 *       （内容 / 模板 / 渲染三层解耦，见 {@link ResumeTemplateCatalog}）；</li>
 *   <li><b>结构规范化</b>——落库前补齐 6 个顶层键，让前端不必到处写防御性判空；</li>
 *   <li><b>归属校验</b>——所有按 ID 的操作都在仓储层用 {@code findByIdAndUserId} 完成，
 *       不做「先 findById 再比 userId」，避免漏判即 IDOR。</li>
 * </ul>
 *
 * <p>为什么不在后端做 PDF：产品评审已判定「打印样式 + 浏览器 print-to-PDF」是 MVP 正解——
 * 浏览器打印天然产出<b>矢量文本层</b>，ATS 可解析；而 html2canvas / jsPDF 产出的是图片，
 * 文本层为零，等于把简历功能做废。后端渲染（Playwright / Flying Saucer）留待后续版本。
 */
@Service
public class ResumeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDocumentService.class);

    /** 单个用户最多保存的简历文档数（防滥用，也避免列表页无限增长） */
    static final int MAX_DOCS_PER_USER = 50;

    /** 单份文档 JSON 的字节上限（结构化简历正常不会超过几十 KB） */
    static final int MAX_DOC_BYTES = 256 * 1024;

    static final int MAX_TITLE_LEN = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResumeDocumentRepository repository;

    public ResumeDocumentService(ResumeDocumentRepository repository) {
        this.repository = repository;
    }

    /** 我的简历文档列表（按最近更新倒序） */
    public List<ResumeDocumentEntity> list(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** 取单份文档，不存在或不属于当前用户一律 404（不区分，避免探测他人资源是否存在） */
    public ResumeDocumentEntity getOwned(String userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该简历文档"));
    }

    /**
     * 新建文档。
     *
     * @param templateId 模板 ID，非法值回落默认模板（用户可能拿着旧链接进来）
     * @param docData    结构化 JSON，可为空（空则用空骨架）
     */
    @Transactional
    public ResumeDocumentEntity create(String userId, String templateId, String title,
                                       String docData, Long sourceResumeId) {
        if (repository.countByUserId(userId) >= MAX_DOCS_PER_USER) {
            throw new BusinessException("简历文档数量已达上限（" + MAX_DOCS_PER_USER + " 份），请先删除不再使用的版本");
        }
        String safeTemplate = normalizeTemplateId(templateId);
        String safeTitle = normalizeTitle(title, "未命名简历");
        String safeData = normalizeDocData(docData, true);

        ResumeDocumentEntity entity = ResumeDocumentEntity.builder()
                .userId(userId)
                .templateId(safeTemplate)
                .title(safeTitle)
                .docData(safeData)
                .sourceResumeId(sourceResumeId)
                .build();
        return repository.save(entity);
    }

    /**
     * 全量更新（编辑器保存）。
     * 只更新传入的非空字段，未传的保持原值——避免前端只改标题时把正文冲掉。
     */
    @Transactional
    public ResumeDocumentEntity update(String userId, Long id, String templateId, String title,
                                       String docData) {
        ResumeDocumentEntity entity = getOwned(userId, id);
        if (templateId != null && !templateId.isBlank()) {
            entity.setTemplateId(normalizeTemplateId(templateId));
        }
        if (title != null && !title.isBlank()) {
            entity.setTitle(normalizeTitle(title, entity.getTitle()));
        }
        if (docData != null) {
            entity.setDocData(normalizeDocData(docData, false));
        }
        return repository.save(entity);
    }

    /** 删除（归属校验在 getOwned 内） */
    @Transactional
    public void delete(String userId, Long id) {
        ResumeDocumentEntity entity = getOwned(userId, id);
        repository.delete(entity);
    }

    /**
     * 模板 ID 规范化：非法 / 空 → 默认模板。
     * 刻意<b>不抛异常</b>：用户点开一个旧链接、或前端版本比后端新时，
     * 报错会让整页打不开，回落到默认模板体验更好。
     */
    static String normalizeTemplateId(String templateId) {
        return ResumeTemplateCatalog.exists(templateId)
                ? templateId.trim()
                : ResumeTemplateCatalog.DEFAULT_TEMPLATE_ID;
    }

    static String normalizeTitle(String title, String fallback) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        return t.length() > MAX_TITLE_LEN ? t.substring(0, MAX_TITLE_LEN) : t;
    }

    /**
     * 结构化数据规范化。
     *
     * <p>做三件事：① 校验是合法 JSON 对象（不是对象就报错，不静默吞掉）；
     * ② 补齐 6 个顶层键（缺失补空对象 / 空数组）；③ 保证 basics 内的 links 是数组。
     *
     * <p>v1.78.0：改为 {@code static} —— 它不依赖任何实例状态，而
     * {@link ResumeAutofillService} 需要在校验阶段复用它。原先只能
     * {@code new ResumeDocumentService(null)} 才能调用，属于为了复用而造对象。
     *
     * @param blankAsSkeleton 空输入时是否返回空骨架（新建允许空，更新不允许传空串）
     */
    static String normalizeDocData(String docData, boolean blankAsSkeleton) {
        if (docData == null || docData.isBlank()) {
            if (blankAsSkeleton) {
                return emptySkeleton().toString();
            }
            throw new BusinessException("简历内容不能为空");
        }
        int bytes = docData.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_DOC_BYTES) {
            throw new BusinessException("简历内容过大（上限 " + (MAX_DOC_BYTES / 1024) + " KB），请精简后再保存");
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(docData);
        } catch (JsonProcessingException e) {
            throw new BusinessException("简历内容格式不合法，请刷新页面后重试");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new BusinessException("简历内容格式不合法，请刷新页面后重试");
        }
        ObjectNode root = (ObjectNode) parsed;

        // basics：必须是对象，且 links 必须是数组
        JsonNode basics = root.get("basics");
        if (basics == null || !basics.isObject()) {
            root.set("basics", MAPPER.createObjectNode());
        } else if (!basics.has("links") || !basics.get("links").isArray()) {
            ((ObjectNode) basics).set("links", MAPPER.createArrayNode());
        }

        // summary：字符串
        if (!root.has("summary") || !root.get("summary").isTextual()) {
            root.put("summary", "");
        }

        // 五个数组段：缺失或类型不对就补空数组（宁可少显示，也不要让前端渲染崩掉）
        for (String key : new String[]{"education", "experience", "projects", "skills", "extras"}) {
            JsonNode node = root.get(key);
            if (node == null || !node.isArray()) {
                ArrayNode arr = MAPPER.createArrayNode();
                root.set(key, arr);
            }
        }
        return root.toString();
    }

    /** 空骨架：结构与正常文档完全一致，前端无需为「新建」写第二套逻辑 */
    public static ObjectNode emptySkeleton() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode basics = root.putObject("basics");
        basics.put("name", "");
        basics.put("title", "");
        basics.put("phone", "");
        basics.put("email", "");
        basics.put("location", "");
        basics.set("links", MAPPER.createArrayNode());
        root.put("summary", "");
        root.set("education", MAPPER.createArrayNode());
        root.set("experience", MAPPER.createArrayNode());
        root.set("projects", MAPPER.createArrayNode());
        root.set("skills", MAPPER.createArrayNode());
        root.set("extras", MAPPER.createArrayNode());
        log.debug("生成空简历骨架");
        return root;
    }
}
