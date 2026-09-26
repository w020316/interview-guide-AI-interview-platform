package com.example.interview.common;

/**
 * 资源不存在（v1.44.0，第三轮 P3-01）。
 *
 * <h2>为什么需要单独一个类型</h2>
 *
 * <p>项目里「资源不存在」此前一律抛 {@link IllegalArgumentException}，被
 * {@link GlobalExceptionHandler} 统一映射成 {@code code=400「请求参数错误：…」}。
 * 而 {@code docs/api-error-contract.md} 里写得很清楚：
 *
 * <pre>
 *   403  无权限 / 账号被禁用
 *   404  资源不存在
 * </pre>
 *
 * <p>也就是说**代码违反了它自己的契约文档**，而且与 {@code GET /api/jobs/{id}}
 * （不存在时正确返回 404）自相矛盾——同一个「找不到」，两个接口给两个码。
 *
 * <h2>为什么 extends IllegalArgumentException</h2>
 *
 * <p>与 {@link BusinessException} extends {@code IllegalStateException} 同样的考虑：
 * 服务层已有大量单测直接断言 {@code isInstanceOf(IllegalArgumentException.class)}，
 * 换一个不相干的父类会波及全部调用点。继承它之后：
 *
 * <ul>
 *   <li>旧断言继续成立；</li>
 *   <li>Spring 的 {@code @ExceptionHandler} 会优先匹配**更具体**的类型，
 *       因此可以单独把 404 语义摘出来，其余 {@code IllegalArgumentException}
 *       仍按 400「请求参数错误」处理。</li>
 * </ul>
 *
 * <p>用法：只用于「按 ID 查不到」这类场景；参数格式错误仍抛 {@code IllegalArgumentException}。
 */
public class ResourceNotFoundException extends IllegalArgumentException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
