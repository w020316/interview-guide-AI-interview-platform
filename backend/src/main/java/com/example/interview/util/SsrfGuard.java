package com.example.interview.util;

import org.springframework.stereotype.Component;

/**
 * {@link SsrUrlValidator} 的可注入入口。
 *
 * <p>控制器直接调用静态方法时，无法在切片测试里构造「合法公网 URL」的场景
 * （校验会真的做 DNS 解析，离线环境必然失败）。抽成 Bean 后，测试可以只替换
 * 这一处校验结果，而抓取与解析链路仍走真实实现。
 *
 * <p>所有「后端代用户抓 URL」的入口都必须经过这里，避免各写一份校验口径。
 */
@Component
public class SsrfGuard {

    public SsrUrlValidator.Result validate(String url) {
        return SsrUrlValidator.validate(url);
    }
}
