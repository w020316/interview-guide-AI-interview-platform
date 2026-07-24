package com.example.interview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /actuator/info 端点贡献者
 *
 * <p>向 /actuator/info 注入应用元数据，便于运维和前端探测当前部署版本：
 * <pre>
 * GET /actuator/info
 * {
 *   "app": {
 *     "name": "interview-guide",
 *     "version": "1.13.0",
 *     "description": "AI 智能面试辅助平台后端",
 *     "buildDate": "2026-07-23"
 *   }
 * }
 * </pre>
 *
 * <p>版本号从 app.info.version 配置读取，与前端 changelog.ts 版本号保持同步。
 * 修改版本时只需更新 application.yml 或环境变量 APP_INFO_VERSION。
 */
@Component
public class AppInfoContributor implements InfoContributor {

    @Value("${app.info.name:interview-guide}")
    private String appName;

    @Value("${app.info.version:1.0.0}")
    private String appVersion;

    @Value("${app.info.description:AI 智能面试辅助平台后端}")
    private String appDescription;

    @Value("${app.info.build-date:}")
    private String buildDate;

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> appInfo = new LinkedHashMap<>();
        appInfo.put("name", appName);
        appInfo.put("version", appVersion);
        appInfo.put("description", appDescription);
        // 构建日期未配置时使用当天日期（CI 构建时通过环境变量注入精确日期）
        String date = (buildDate == null || buildDate.isBlank())
                ? LocalDate.now().toString() : buildDate;
        appInfo.put("buildDate", date);

        builder.withDetail("app", appInfo);
    }
}
