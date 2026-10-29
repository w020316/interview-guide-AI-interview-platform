package com.example.interview.service.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 思科官方招聘站（Workday，国内中国区岗位，v1.88.0）
 *
 * <p>Workday 站点 {@code cisco.wd5.myworkdayjobs.com/Cisco_Careers}，
 * 实测中国区在招 11 条（北京 / 杭州 / 上海 / 深圳）。站点没有国家级 China facet，
 * 只有城市级「Beijing, China」等选项，基类会自动识别并按城市 id 过滤。
 */
@Component
public class CiscoJobProvider extends AbstractWorkdayJobProvider {

    private static final String PLATFORM = "思科招聘";

    /** 每轮拉几页（20 条/页），默认 1：11 条一轮即可覆盖 */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.cisco.pages-per-refresh:1}")
    private int pagesPerRefresh = 1;

    public CiscoJobProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    protected String workdayHost() {
        return "cisco.wd5.myworkdayjobs.com";
    }

    @Override
    protected String tenant() {
        return "cisco";
    }

    @Override
    protected String site() {
        return "Cisco_Careers";
    }

    @Override
    protected String companyName() {
        return "Cisco";
    }

    @Override
    protected String externalIdPrefix() {
        return "cs";
    }

    @Override
    protected int pagesPerRefresh() {
        return pagesPerRefresh;
    }
}
