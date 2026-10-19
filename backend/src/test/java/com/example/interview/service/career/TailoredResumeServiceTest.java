package com.example.interview.service.career;

import com.example.interview.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TailoredResumeService} 单元测试（v1.74.0 定制简历事实溯源 / v1.74.1 校验放宽）
 *
 * <p>覆盖点：
 * <ol>
 *   <li>合法 JSON 原样返回，findings 为空数组；</li>
 *   <li>主叙事不可用（缺 rewrittenBullets / 空数组 / 条目全坏）触发重试一次，仍不可用则抛
 *       {@link BusinessException}——不再把兜底串当成功结果下发；</li>
 *   <li>v1.74.1 回归用例：次要字段（suggestions / matchedKeywords / missingKeywords / summary）
 *       缺失或类型不对时**自动补齐并成功返回**，只有主叙事不可用才失败；单条 bullet 坏只丢那一条；</li>
 *   <li>错误归因分叉：空响应报「AI 服务暂时不可用」，输出结构问题才报「格式异常」；</li>
 *   <li>数字溯源：编造的数字整条剔除并写 findings；原文已有的数字与中文数字（三个 ↔ 3 个）不得误杀；</li>
 *   <li>{@code stripUnsourcedNumbers} 自身异常时原样返回（安全网不能带崩主流程）；</li>
 *   <li>{@code chineseNumeral} 0-99 边界。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TailoredResumeService 定制简历单元测试")
class TailoredResumeServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @InjectMocks
    private TailoredResumeService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条结构完全合法、且数字都能在原文找到的模型输出 */
    private static final String VALID_JSON =
            "{\"matchedKeywords\":[\"Spring Boot\"],\"missingKeywords\":[\"Kafka\"],"
                    + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化，响应时间下降 40%\","
                    + "\"rewritten\":\"负责订单系统性能优化，响应时间下降 40%，支撑大促峰值\","
                    + "\"reason\":\"贴合 JD 对高并发经验的要求\"}],"
                    + "\"summary\":\"懂业务的 Java 后端\",\"suggestions\":[\"补充 QPS 数据\"]}";

    /** ChatClient 同步调用链 stub：prompt() → user() → call() → content() */
    private void stubChatClient(String response) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(response);
    }

    /** 首次返回 first、重试返回 second 的 stub */
    private void stubChatClientSequence(String first, String second) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(first, second);
    }

    private JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("合法 JSON：条目保留，findings 补为空数组")
    void tailor_validJson_keepsBullets() throws Exception {
        stubChatClient(VALID_JSON);

        String result = service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "熟悉 Spring Boot");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("rewrittenBullets").get(0).get("rewritten").asText()).contains("40%");
        assertThat(node.get("findings").isArray()).isTrue();
        assertThat(node.get("findings").size()).isZero();
        assertThat(node.get("summary").asText()).isEqualTo("懂业务的 Java 后端");
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("数字溯源 · 编造：原文没有的 40% 导致整条改写被剔除并写入 findings")
    void tailor_fabricatedNumber_removesBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化\","
                + "\"rewritten\":\"负责订单系统性能优化，响应时间下降 40%\",\"reason\":\"量化结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责订单系统性能优化", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        JsonNode findings = node.get("findings");
        assertThat(findings.size()).isEqualTo(1);
        assertThat(findings.get(0).get("type").asText()).isEqualTo("unsourced_number");
        assertThat(findings.get(0).get("tokens").toString()).contains("40%");
        assertThat(findings.get(0).get("rewritten").asText()).contains("响应时间下降 40%");
    }

    @Test
    @DisplayName("数字溯源 · 防自证：模型在 original 里自写数字不能给 rewritten 背书")
    void tailor_originalCannotVouchForRewritten() throws Exception {
        // 用户原文只有「负责订单系统性能优化」；模型把 40% 同时写进 original 和 rewritten，
        // 若把 original 并入溯源基准，这条编造就会被放行——这是本批要堵的红线本身
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统性能优化，响应时间下降 40%\","
                + "\"rewritten\":\"主导订单系统性能优化，响应时间下降 40%\",\"reason\":\"量化结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责订单系统性能优化", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        assertThat(node.get("findings").size()).isEqualTo(1);
        assertThat(node.get("findings").get(0).get("tokens").toString()).contains("40%");
    }

    @Test
    @DisplayName("数字溯源 · 合法：原文已有 40% 时条目保留，findings 为空")
    void tailor_sourcedNumber_keepsBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"响应时间下降 40%\","
                + "\"rewritten\":\"响应时间下降 40%，覆盖核心链路\",\"reason\":\"突出结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("响应时间下降 40%", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("数字溯源 · 中文数字兼容：原文「三个」不得误杀改写后的「3 个」")
    void tailor_chineseNumeral_keepsBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责三个核心模块\","
                + "\"rewritten\":\"负责 3 个核心模块的设计与落地\",\"reason\":\"关键词前置\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责三个核心模块", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("数字溯源 · 年份不能给个位数背书：原文「2023 年毕业」不得放行改写里的「3」")
    void tailor_yearCannotVouchForSingleDigit() throws Exception {
        // v1.74.2 线上问题：子串匹配下「2023」包含「3」，于是「管理 3 个团队」被判为有来源。
        // 几乎每份简历都有年份，等于 0/1/2/3/19/20/21/22/23/24/25/26 这批数字的闸门形同虚设。
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"参与课程设计\","
                + "\"rewritten\":\"管理 3 个团队完成课程设计\",\"reason\":\"突出规模\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("2023 年毕业，参与课程设计", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        assertThat(node.get("findings").size()).isEqualTo(1);
        assertThat(node.get("findings").get(0).get("tokens").toString()).contains("3");
    }

    @Test
    @DisplayName("数字溯源 · 大数不能给小数字背书：原文「QPS 3000」不得放行「30%」")
    void tailor_bigNumberCannotVouchForSmallOne() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责性能优化\","
                + "\"rewritten\":\"负责性能优化，提升 30%\",\"reason\":\"量化结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("QPS 峰值 3000", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isZero();
        assertThat(node.get("findings").get(0).get("tokens").toString()).contains("30%");
    }

    @Test
    @DisplayName("数字溯源 · 小数尾零不误杀：原文 40% / 改写 40.0% 应保留")
    void tailor_decimalTrailingZeros_notFalsePositive() throws Exception {
        // 归一化前「40.0」在原文里找不到字面「40.0」，会被当成编造而整条误杀
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"响应时间下降 40%\","
                + "\"rewritten\":\"响应时间下降 40.0%，覆盖核心链路\",\"reason\":\"突出结果\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("响应时间下降 40%", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("数字溯源 · 边界内正常命中：原文「负责 3 个模块」放行「负责 3 个核心模块」")
    void tailor_numberWithinBoundary_keepsBullet() throws Exception {
        stubChatClient("{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责 3 个模块\","
                + "\"rewritten\":\"负责 3 个核心模块的设计\",\"reason\":\"关键词前置\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}");

        String result = service.tailor("负责 3 个模块", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("findings").size()).isZero();
    }

    @Test
    @DisplayName("结构非法：第一次缺 rewrittenBullets，重试一次拿到合法结果")
    void tailor_invalidStructure_retriesOnce() throws Exception {
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(missingBullets, VALID_JSON);

        String result = service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "");

        assertThat(parse(result).get("rewrittenBullets").size()).isEqualTo(1);
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("重试后仍非法：抛 BusinessException，文案面向用户")
    void tailor_stillInvalid_throws() {
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(missingBullets, missingBullets);

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("定制简历结果格式异常");
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("结构非法：rewrittenBullets 为空数组也不算成功（空面板必须走重试/报错）")
    void tailor_emptyBullets_throws() {
        // 变异测试（v1.74.0 验证阶段）发现：删掉结构校验里的 bullets.isEmpty()
        // 判断后全部用例仍绿——说明「空数组」这条分支当时没有任何测试锁住。
        // 而空数组恰恰是最隐蔽的失败形态：结构合法、HTTP 200、前端 v-if 全部落空，
        // 用户只看到一块空面板，既不知道失败也不知道为什么。
        String emptyBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[],\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(emptyBullets, emptyBullets);

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("定制简历结果格式异常");
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("空响应：走重试，最终抛「AI 服务暂时不可用」而不是「格式异常」")
    void tailor_emptyResponse_throwsInsteadOfFallback() {
        // v1.74.1：空响应是链路故障（模型没回内容），不是「模型输出结构不对」。
        // 两种故障的处置方式完全不同，文案必须分叉——这条用例就是错误归因的守卫。
        stubChatClient("");

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用")
                .hasMessageNotContaining("格式异常");
    }

    @Test
    @DisplayName("次要字段缺失：缺 suggestions 不判失败，补空数组且保留 bullets")
    void tailor_missingSuggestions_stillSucceeds() throws Exception {
        // v1.74.0 线上回归：模型偶发漏 suggestions，整份结果被判非法 → 重试 → 503（实测 40%）
        String json = "{\"matchedKeywords\":[\"Spring Boot\"],\"missingKeywords\":[\"Kafka\"],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统\",\"rewritten\":\"负责订单系统架构\","
                + "\"reason\":\"贴合 JD\"}],\"summary\":\"懂业务的 Java 后端\"}";
        stubChatClient(json);

        String result = service.tailor("负责订单系统", "Java 后端", "");

        JsonNode node = parse(result);
        assertThat(node.get("suggestions").isArray()).isTrue();
        assertThat(node.get("suggestions").size()).isZero();
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        // 不重试：次要字段缺失属于「可修正」，不该多消耗一次 AI 调用
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("次要字段缺失：缺 matchedKeywords + missingKeywords 不判失败，两个都补空数组")
    void tailor_missingKeywordArrays_stillSucceeds() throws Exception {
        String json = "{\"rewrittenBullets\":[{\"original\":\"负责订单系统\",\"rewritten\":\"负责订单系统架构\","
                + "\"reason\":\"贴合 JD\"}],\"summary\":\"懂业务的 Java 后端\",\"suggestions\":[]}";
        stubChatClient(json);

        JsonNode node = parse(service.tailor("负责订单系统", "Java 后端", ""));

        assertThat(node.get("matchedKeywords").isArray()).isTrue();
        assertThat(node.get("matchedKeywords").size()).isZero();
        assertThat(node.get("missingKeywords").isArray()).isTrue();
        assertThat(node.get("missingKeywords").size()).isZero();
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("summary 为 null：不判失败，补空串")
    void tailor_nullSummary_stillSucceeds() throws Exception {
        String json = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统\",\"rewritten\":\"负责订单系统架构\","
                + "\"reason\":\"贴合 JD\"}],\"summary\":null,\"suggestions\":[]}";
        stubChatClient(json);

        JsonNode node = parse(service.tailor("负责订单系统", "Java 后端", ""));

        assertThat(node.get("summary").isTextual()).isTrue();
        assertThat(node.get("summary").asText()).isEmpty();
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("单条 bullet 缺 rewritten：只丢坏的那条，好的保留")
    void tailor_oneBadBullet_dropsOnlyThatOne() throws Exception {
        String json = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":["
                + "{\"original\":\"负责订单系统\",\"rewritten\":\"负责订单系统架构\",\"reason\":\"贴合 JD\"},"
                + "{\"original\":\"负责支付模块\",\"reason\":\"缺 rewritten 字段\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClient(json);

        JsonNode node = parse(service.tailor("负责订单系统", "Java 后端", ""));

        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
        assertThat(node.get("rewrittenBullets").get(0).get("original").asText()).isEqualTo("负责订单系统");
    }

    @Test
    @DisplayName("bullets 全部缺 rewritten：主叙事不可用，抛格式异常")
    void tailor_allBulletsBroken_throws() {
        String json = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[{\"original\":\"负责订单系统\"},{\"original\":\"负责支付模块\"}],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(json, json);

        assertThatThrownBy(() -> service.tailor("负责订单系统", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式异常");
        verify(callResponseSpec, times(2)).content();
    }

    @Test
    @DisplayName("stripUnsourcedNumbers：非法 JSON 入参不抛异常，原样返回")
    void stripUnsourcedNumbers_invalidJson_returnsAsIs() {
        String broken = "抱歉，我无法完成这个请求。";

        assertThat(TailoredResumeService.stripUnsourcedNumbers(broken, "负责订单系统")).isEqualTo(broken);
        assertThat(TailoredResumeService.stripUnsourcedNumbers(null, "负责订单系统")).isNull();
    }

    @Test
    @DisplayName("stripUnsourcedNumbers：缺 findings 字段时补空数组")
    void stripUnsourcedNumbers_addsFindingsField() throws Exception {
        // 基准刻意用阿拉伯数字而非「三个」：本用例的意图是「缺 findings 字段时补空数组」，
        // 若依赖中文数字逻辑，一旦有人动 chineseNumeral 这条会跟着红，诊断信息会误导人
        String json = "{\"rewrittenBullets\":[{\"original\":\"负责 3 个模块\","
                + "\"rewritten\":\"负责 3 个核心模块\",\"reason\":\"\"}],\"summary\":\"\"}";

        JsonNode node = parse(TailoredResumeService.stripUnsourcedNumbers(json, "负责 3 个模块"));

        assertThat(node.get("findings").isArray()).isTrue();
        assertThat(node.get("findings").size()).isZero();
        assertThat(node.get("rewrittenBullets").size()).isEqualTo(1);
    }

    /* ─────────────────────────────────────────────────────────────
       v1.75.0：数值集合比对（原文与改写落到同一个 NumKey 空间）
       ───────────────────────────────────────────────────────────── */

    /**
     * 0–9999 → 中文数字。与生产侧 {@code parseChineseNumeral} 方向相反，专供往返验证。
     *
     * <p>为什么值得在测试里写一个生成器：手写中文数字解析器最容易在「零的占位」上出错
     * （{@code 一百零五} vs {@code 一百五}），30 条手写用例覆盖不到 9000 个组合的边角，
     * 而往返断言一次就能把它们全部跑一遍。
     */
    private static String toChinese(int n) {
        if (n == 0) {
            return "零";
        }
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        StringBuilder sb = new StringBuilder();
        int qian = n / 1000;
        int bai = (n / 100) % 10;
        int shi = (n / 10) % 10;
        int ge = n % 10;
        if (qian > 0) {
            sb.append(d[qian]).append('千');
        }
        if (bai > 0) {
            sb.append(d[bai]).append('百');
        }
        if (shi > 0) {
            // 百位缺失时要补「零」：一千零一十
            if (bai == 0 && sb.length() > 0) {
                sb.append('零');
            }
            // 「十」「十二」不写「一十」「一十二」
            if (shi == 1 && sb.length() == 0) {
                sb.append('十');
            } else {
                sb.append(d[shi]).append('十');
            }
        } else if (ge > 0 && sb.length() > 0) {
            // 一百零五、一千零五
            sb.append('零');
        }
        if (ge > 0) {
            sb.append(d[ge]);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("中文数字解析：0-9999 全量往返一致")
    void parseChineseNumeral_roundTripAllValues() {
        for (int n = 0; n <= 9999; n++) {
            String cn = toChinese(n);
            assertThat(TailoredResumeService.parseChineseNumeral(cn))
                    .as("%d 的中文写法「%s」解析失败", n, cn)
                    .isEqualTo(BigDecimal.valueOf(n));
        }
    }

    @Test
    @DisplayName("中文数字解析：不支持的形态返回 null（不猜）")
    void parseChineseNumeral_rejectsAmbiguousForms() {
        // 连写约数：三五天不是 35
        assertThat(TailoredResumeService.parseChineseNumeral("三五")).isNull();
        // 单个「万」不是 10000（「万一」是「如果」）
        assertThat(TailoredResumeService.parseChineseNumeral("万")).isNull();
        assertThat(TailoredResumeService.parseChineseNumeral("亿")).isNull();
        // 单位乱序
        assertThat(TailoredResumeService.parseChineseNumeral("十百")).isNull();
    }

    /** 把一句改写包成最小合法 JSON，供矩阵用例复用 */
    private static String bulletJson(String rewritten) {
        return "{\"rewrittenBullets\":[{\"original\":\"原条目\",\"rewritten\":\"" + rewritten
                + "\",\"reason\":\"\"}],\"summary\":\"\"}";
    }

    /** 这条改写是否被保留（true=保留，false=被溯源闸门剔除） */
    private boolean kept(String source, String rewritten) throws Exception {
        JsonNode node = parse(TailoredResumeService.stripUnsourcedNumbers(bulletJson(rewritten), source));
        return node.get("rewrittenBullets").size() == 1;
    }

    @Test
    @DisplayName("数值集合矩阵：同值不同写法保留，编造剔除")
    void stripUnsourcedNumbers_valueSetMatrix() throws Exception {
        // 每行：原文 | 改写 | 期望（1=保留 / 0=剔除）| 这条在防什么
        String[][] cases = {
                {"2023 年毕业", "管理 3 个团队", "0", "年份不能给个位数背书"},
                {"QPS 峰值 3000", "提升 30%", "0", "大数不能给小数背书"},
                {"负责三个模块", "负责四十个模块", "0", "中文数字也要核对"},
                {"性能提升", "性能提升百分之四十", "0", "百分之X 也要核对"},
                {"带一百二十人团队", "带 120 人团队", "1", "三位数中文不能误杀"},
                {"响应时间下降 40%", "响应时间下降 40.0%", "1", "小数尾零不能误杀"},
                {"４０ 次压测", "40 次压测", "1", "全角数字同值必须保留"},
                {"40 人", "40%", "0", "计数不能说成比率"},
                {"统一管理", "1 个模块", "0", "虚词里的「一」不能背书"},
                {"负责三个模块", "负责 3 个核心模块", "1", "中 → 阿"},
                {"负责 3 个模块", "负责三个模块", "1", "阿 → 中"},
                {"服务十万用户", "服务 100000 用户", "1", "十万"},
                {"服务十万用户", "服务 10 万用户", "1", "万作量级后缀"},
                {"两千人", "2000 人", "1", "千"},
                {"十二个接口", "12 个接口", "1", "十位"},
                {"十个模块", "10 个模块", "1", "十"},
                {"两个团队", "2 个团队", "1", "两"},
                {"一百零五个用例", "105 个用例", "1", "零占位"},
                {"三千五", "3500", "1", "口语省略式"},
                {"一万二", "12000", "1", "口语省略式（万）"},
                {"服务三亿用户", "3 亿用户", "1", "亿"},
                {"3,000 QPS", "3000 QPS", "1", "千分位（最易误杀）"},
                {"3 万", "30000", "1", "阿拉伯 + 万"},
                {"1.50 年", "1.5 年", "1", "尾零归一"},
                {"40 %", "40%", "1", "数字与百分号间有空格"},
                {"三个月", "3 个月", "1", "月作量词"},
                {"半年", "0.5 年", "0", "不支持的「半」整段跳过"},
                {"第一作者", "第 1 作者", "1", "序数整类跳过"},
                {"进一步优化", "1 步优化", "0", "「步」不是量词"},
                {"万一失败", "1 个方案", "0", "「万一」不是 10000"},
                {"十分熟悉", "10 个模块", "0", "「分」不是量词"},
                {"3 个模块", "30 个模块", "0", "10 倍放大"},
        };
        for (String[] c : cases) {
            assertThat(kept(c[0], c[1]))
                    .as("原文「%s」改写「%s」——%s", c[0], c[1], c[3])
                    .isEqualTo("1".equals(c[2]));
        }
    }

    @Test
    @DisplayName("真实简历 + 忠实改写：一条都不许误杀")
    void stripUnsourcedNumbers_realisticResume_noFalseKill() throws Exception {
        // 误杀比漏放更伤信任：用户看到「我的真实数据被删了」会直接不信这个功能。
        // 这条用一段带年份、QPS、百分比、人数、模块数的真实简历做基准，
        // 改写只做重排与措辞调整、不新增任何数字，必须一条都不剔除。
        String source = "2023 年毕业，负责订单系统性能优化，QPS 峰值 3000，响应时间下降 40%，"
                + "主导 3 个模块重构，带 5 人小组";
        String rewritten = "主导订单系统性能优化，QPS 峰值 3000，响应时间下降 40%，"
                + "带 5 人小组完成 3 个模块重构";

        JsonNode node = parse(TailoredResumeService.stripUnsourcedNumbers(bulletJson(rewritten), source));

        assertThat(node.get("rewrittenBullets").size()).as("忠实改写被误杀了").isEqualTo(1);
        assertThat(node.get("findings").size()).as("不该产生 findings").isZero();
    }

    @Test
    @DisplayName("findings.tokens 放改写里的原样写法，用户能拿去搜")
    void stripUnsourcedNumbers_tokensKeepSurfaceForm() throws Exception {
        JsonNode node = parse(TailoredResumeService.stripUnsourcedNumbers(
                bulletJson("性能提升百分之四十，覆盖核心链路"), "性能提升"));

        assertThat(node.get("rewrittenBullets").size()).isZero();
        assertThat(node.get("findings").get(0).get("tokens").get(0).asText()).isEqualTo("百分之四十");
    }

    /* ─────────────────────────────────────────────────────────────
       v1.75.0：重试耗时预算
       ───────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("重试预算：已耗时超预算时不重试，仍报「格式异常」")
    void tailor_budgetExhausted_skipsRetry() {
        service.retryBudgetMs = 0;
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClient(missingBullets);

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式异常");
        // 预算耗尽 → 只打了 1 次模型，没有第二次
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("重试预算：超预算且无内容时报链路故障，不是格式异常")
    void tailor_budgetExhaustedWithBlank_reportsChainFailure() {
        service.retryBudgetMs = 0;
        stubChatClient("");

        assertThatThrownBy(() -> service.tailor("负责订单系统性能优化", "Java 后端", ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用")
                .hasMessageNotContaining("格式异常");
        verify(callResponseSpec, times(1)).content();
    }

    @Test
    @DisplayName("重试换提示词：首次返回空数组时，第二次要求「至少 1 条」")
    void tailor_retryWithHint_whenFirstHasNoBullets() throws Exception {
        String emptyBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"rewrittenBullets\":[],\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(emptyBullets, VALID_JSON);
        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);

        service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "");

        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(0)).doesNotContain("【上一次的问题】");
        // 重试用同一个 prompt 只会让模型以同样方式再错一次，所以第二次必须带上次的错因
        assertThat(prompts.getAllValues().get(1))
                .contains("【上一次的问题】")
                .contains("至少输出 1 条改写条目");
    }

    @Test
    @DisplayName("重试换提示词：首次无返回时，第二次要求直接输出 JSON")
    void tailor_retryWithHint_whenFirstIsBlank() throws Exception {
        stubChatClientSequence("", VALID_JSON);
        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);

        service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "");

        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1))
                .contains("【上一次的问题】")
                .contains("没有返回任何内容");
    }

    @Test
    @DisplayName("重试换提示词：两次请求的 prompt 必须不同")
    void tailor_retryPromptMustDiffer() throws Exception {
        String missingBullets = "{\"matchedKeywords\":[],\"missingKeywords\":[],"
                + "\"summary\":\"\",\"suggestions\":[]}";
        stubChatClientSequence(missingBullets, VALID_JSON);
        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);

        service.tailor("负责订单系统性能优化，响应时间下降 40%", "Java 后端", "");

        verify(requestSpec, times(2)).user(prompts.capture());
        assertThat(prompts.getAllValues().get(1)).isNotEqualTo(prompts.getAllValues().get(0));
    }
}
