package cn.self.studyspringc.integration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP 客户端通用配置类
 * <p>
 * 核心作用：
 * 1. 定制并向 Spring 容器注册远程 HTTP 请求客户端（RestClient 单例 Bean）。
 * 2. 统一配置目标服务的基地址（Base URL），实现代码与配置解耦。
 * 3. 配置底层双重超时防御机制（连接超时 3s + 读取超时 5s），防止第三方服务故障导致本地线程耗尽（防服务雪崩）。
 */
@Configuration
public class HttpClientConfig {

    /**
     * 构建并向 Spring IoC 容器注册名为 "postRestClient" 的 HTTP 客户端单例对象。
     *
     * @param builder Spring Boot 自动装配好的 RestClient.Builder 原型对象（已默认集成 JSON 转换器、可观测性监控等）
     * @param baseUrl 从 application.yaml (external.post-api.base-url) 中读取的远程接口根路径
     * @return 经过定制装配并完成构建的 RestClient 最终成品实例
     */
    @Bean
    public RestClient postRestClient(
            RestClient.Builder builder,
            @Value("${external.post-api.base-url}") String baseUrl
    ) {
        // 1. 创建底层 Java 11+ 原生 HttpClient，并设置【连接超时时间为 3 秒】
        // 连接超时（Connect Timeout）：向目标服务器发起 TCP 三次握手建立连接的最大等待时间，超过 3s 未连上立即中断。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // 2. 使用适配器模式，将 JDK 原生 HttpClient 包装为 Spring 规范的请求工厂 (ClientHttpRequestFactory)
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // 设置【读取超时时间为 5 秒】
        // 读取超时（Read Timeout）：TCP 连接建立且请求发出后，等待对方返回完整数据包的最大等待时间，防止慢请求卡死线程。
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        // 3. 利用 Spring 提供的 builder 进行加工组装：
        //    - .baseUrl(...)：绑定基础请求 URL 前缀，后续业务直接写相对路径即可
        //    - .requestFactory(...)：挂载定制好的超时工厂
        //    - .build()：一键构建出最终的、线程安全的 RestClient 成品实例并返回
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
