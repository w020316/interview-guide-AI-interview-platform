package com.example.interview.service.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 英伟达官方招聘站（Workday，国内中国区岗位，v1.88.0）
 *
 * <p>Workday 站点 {@code nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite}，
 * 实测中国区在招 245 条（北京 92 / 上海 216 / 深圳 48，岗位可跨城市）。
 * 站点自身公开接口，无需 Key / Cookie；接口与字段约定见
 * {@link AbstractWorkdayJobProvider}。
 */
@Component
public class NvidiaJobProvider extends AbstractWorkdayJobProvider {

    private static final String PLATFORM = "英伟达招聘";

    /** 每轮拉几页（20 条/页），默认 1：245 条约 13 轮 × 6h/轮 ≈ 3.25 天覆盖 */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.nvidia.pages-per-refresh:1}")
    private int pagesPerRefresh = 1;

    public NvidiaJobProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    protected String workdayHost() {
        return "nvidia.wd5.myworkdayjobs.com";
    }

    @Override
    protected String tenant() {
        return "nvidia";
    }

    @Override
    protected String site() {
        return "NVIDIAExternalCareerSite";
    }

    @Override
    protected String companyName() {
        return "NVIDIA";
    }

    @Override
    protected String externalIdPrefix() {
        return "nv";
    }

    @Override
    protected int pagesPerRefresh() {
        return pagesPerRefresh;
    }
}
