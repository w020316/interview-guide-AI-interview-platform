package com.example.interview.service.resume;

import java.util.List;

/**
 * 简历模板目录（v1.67.0）
 *
 * <p>模板是<b>产品资产</b>而不是用户数据，因此用代码常量管理，不建表：
 * 模板随版本迭代、需要 code review，放进数据库反而失去版本控制。
 * 前端通过 {@code GET /api/resume/templates} 读取同一份元数据，
 * <b>模板 ID 与版式实现由本类与前端 {@code utils/resumeDoc.ts} 两侧共同约定</b>——
 * 新增模板必须同时改两处，否则前端渲染会落到兜底模板。
 *
 * <p>借鉴来源（仅借鉴思路，未引入任何代码）：
 * <ul>
 *   <li>{@code reactive-resume/reactive-resume}（MIT）：内容 / 模板 / 渲染三层解耦，
 *       模板可按 one-column / two-column / ATS-safe 筛选；</li>
 *   <li>{@code jsonresume/resume-schema}（MIT）：分段字段划分，本项目裁到 6 段；</li>
 *   <li>{@code salomonelli/best-resume-ever}（MIT）：数据与模板组件分离。</li>
 * </ul>
 *
 * <p>ATS（简历解析系统）友好性分级说明：
 * <ul>
 *   <li>{@code HIGH} —— 纯线性 DOM、无分栏无表格，解析器逐段读取即可；</li>
 *   <li>{@code MEDIUM} —— 双栏用 CSS Grid（非 float/absolute），DOM 顺序仍是
 *       「Header → 左栏 → 右栏」，解析器不会交错串行；侧栏只放无序列表型内容
 *       （技能 / 链接 / 证书），即使被追加到文末也不失真。</li>
 * </ul>
 */
public final class ResumeTemplateCatalog {

    private ResumeTemplateCatalog() {
    }

    /** 未指定模板时的兜底模板（ATS 最友好） */
    public static final String DEFAULT_TEMPLATE_ID = "classic-single";

    /**
     * 模板元数据。
     *
     * @param id          模板 ID（前后端约定的稳定标识，不可随意改名）
     * @param name        展示名
     * @param scene       适用场景（一句话，给用户做选择依据）
     * @param layout      {@code one-column} / {@code two-column}
     * @param atsLevel    {@code HIGH} / {@code MEDIUM}
     * @param recommended 是否默认推荐（全站只允许一套为 true）
     * @param description 详细说明
     * @param sections    区块顺序（决定渲染顺序；不同模板顺序不同）
     */
    public record Template(
            String id,
            String name,
            String scene,
            String layout,
            String atsLevel,
            boolean recommended,
            String description,
            List<String> sections
    ) {
    }

    /** 六个可渲染区块的稳定标识 */
    public static final String SEC_SUMMARY = "summary";
    public static final String SEC_EXPERIENCE = "experience";
    public static final String SEC_PROJECTS = "projects";
    public static final String SEC_EDUCATION = "education";
    public static final String SEC_SKILLS = "skills";
    public static final String SEC_EXTRAS = "extras";

    private static final List<Template> TEMPLATES = List.of(
            new Template(
                    "classic-single",
                    "单栏·通用",
                    "通用岗位，技术 / 产品 / 运营都适用",
                    "one-column",
                    "HIGH",
                    true,
                    "纯线性排版，无表格、无分栏，机器解析最稳。不确定选哪套时选它。",
                    List.of(SEC_SUMMARY, SEC_EXPERIENCE, SEC_PROJECTS,
                            SEC_EDUCATION, SEC_SKILLS, SEC_EXTRAS)
            ),
            new Template(
                    "campus-single",
                    "单栏·应届",
                    "校招 / 实习，教育背景前置",
                    "one-column",
                    "HIGH",
                    false,
                    "把教育背景提到最前，突出学校、专业、GPA 与主修课程，适合无正式工作经历的应届生。",
                    List.of(SEC_SUMMARY, SEC_EDUCATION, SEC_EXPERIENCE, SEC_PROJECTS,
                            SEC_SKILLS, SEC_EXTRAS)
            ),
            new Template(
                    "tech-two-col",
                    "双栏·技术",
                    "研发岗，技能栈密集",
                    "two-column",
                    "MEDIUM",
                    false,
                    "左主栏放经历与项目，右侧栏收拢技能、链接与证书。技能条目多时更省空间；投严格 ATS 的系统建议改用「单栏·通用」。",
                    List.of(SEC_SUMMARY, SEC_EXPERIENCE, SEC_PROJECTS,
                            SEC_EDUCATION, SEC_SKILLS, SEC_EXTRAS)
            ),
            new Template(
                    "intl-en",
                    "单栏·海外英文",
                    "外企 / 海外岗位，英文简历",
                    "one-column",
                    "HIGH",
                    false,
                    "按英文简历惯例：无照片、无性别年龄籍贯，Summary 前置，章节名用 Experience / Education / Skills 直接命中主流招聘系统。",
                    List.of(SEC_SUMMARY, SEC_EXPERIENCE, SEC_PROJECTS,
                            SEC_EDUCATION, SEC_SKILLS, SEC_EXTRAS)
            ),
            new Template(
                    "compact-one-page",
                    "紧凑·一页",
                    "经历较多、需要压到一页",
                    "one-column",
                    "HIGH",
                    false,
                    "缩小字号与间距，把内容压进一页。正文字号低于常规阶梯，阅读舒适度换版面紧凑，正式投递前建议先看预览确认。",
                    List.of(SEC_SUMMARY, SEC_EXPERIENCE, SEC_PROJECTS,
                            SEC_EDUCATION, SEC_SKILLS, SEC_EXTRAS)
            )
    );

    /** 全部模板（顺序即前端展示顺序） */
    public static List<Template> all() {
        return TEMPLATES;
    }

    /** 按 ID 查模板，不存在返回 null */
    public static Template find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim();
        for (Template t : TEMPLATES) {
            if (t.id().equals(normalized)) {
                return t;
            }
        }
        return null;
    }

    /** 模板是否存在 */
    public static boolean exists(String id) {
        return find(id) != null;
    }
}
