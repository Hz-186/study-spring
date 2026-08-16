package cn.self.studyspringc.integration.post;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 远程文章接口客户端
 * <p>
 * 核心职责：
 * 1. 封装对外部文章系统的具体 HTTP 请求细节。
 * 2. 注入在 HttpClientConfig 中定制好的 RestClient 单例成品，开箱即用。
 * 3. 统一拦截网络异常与 HTTP 错误码，转化为业务层可识别的 RemoteApiException。
 */
@Component
@RequiredArgsConstructor
public class PostClient {

    // 注入的是在 HttpClientConfig 中配置好的 RestClient 成品实例（按 Bean 变量名自动匹配）
    private final RestClient postRestClient;

    /**
     * 根据文章 ID 获取远程文章详情
     *
     * @param id 文章 ID
     * @return 解析后的 RemotePost 对象
     * @throws RemoteApiException 当网络不可达、第三方报错或返回空时抛出
     */
    public RemotePost get(Long id) {
        try {
            // 链式调用发送 GET 请求：
            // 1. .uri(...)：结合 Config 中的 baseUrl，自动拼接为完整的请求路径（如 https://.../posts/1）
            // 2. .retrieve()：执行请求并提取 HTTP 响应
            // 3. .body(...)：自动将返回的 JSON 字符串反序列化为 RemotePost 实体对象
            RemotePost post = postRestClient.get()
                    .uri("/posts/{id}", id)
                    .retrieve()
                    .body(RemotePost.class);

            if (post == null) {
                throw new RemoteApiException("远程文章接口返回空响应", null);
            }
            return post;
        } catch (RestClientResponseException exception) {
            // 捕获 HTTP 响应错误（例如第三方接口返回 404 Not Found、500 Internal Server Error 等）
            throw new RemoteApiException(
                    "远程文章接口返回状态 " + exception.getStatusCode(),
                    exception
            );
        } catch (ResourceAccessException exception) {
            // 捕获底层的 I/O 与网络异常（例如连接超时、读取超时、DNS 解析失败、网络断开等）
            throw new RemoteApiException("无法访问远程文章接口", exception);
        }
    }
}
