# 08. 生产级运维监控与自动化 API 文档：SpringDoc OpenAPI 3 + Actuator

> **模块定位**：生产交付与微服务可观测性（Observability）层  
> **核心技术栈**：SpringDoc OpenAPI 3 (Swagger 3) / Spring Boot Actuator / Micrometer Prometheus  
> **学习目标**：掌握现代云原生架构下的接口文档自动化生成与服务在线调试；掌握基于 Spring Boot Actuator 的健康检查（Health Probes）、JVM/CPU/连接池指标监控、Prometheus 抓取对接，以及编写自定义健康指示器与自定义业务指标打点（Counter/Timer）。

---

## 一、 为什么工业级交付必须包含文档与可观测性？

在企业级研发团队中，代码写完并不代表任务结束：
1. **跨团队协同成本（API 文档）**：前端、移动端、测试人员需要清晰规范的接口文档。传统的 Word / Markdown 手写文档极易滞后甚至过时。通过 **SpringDoc OpenAPI 3**，在代码注解中即时维护，自动生成交互式调试 UI。
2. **线上黑盒与故障失联（生产可观测性）**：当服务部署在 Kubernetes (K8s) 或服务器集群中，运维需要实时探测服务存活状态（Liveness/Readiness）。当出现接口变慢或内存泄漏时，通过 **Spring Boot Actuator + Prometheus + Grafana** 可以在几秒内精准定位 JVM GC 频繁或数据库连接池耗尽。

---

## 二、 依赖引入配置（`build.gradle`）

```groovy
dependencies {
    // 1. SpringDoc OpenAPI 3 (Swagger UI 现代化组件，专为 Spring Boot 3/4 设计)
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'

    // 2. Spring Boot 官方运维监控 Starter
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // 3. Micrometer Prometheus 指标适配器（用于对接 Prometheus + Grafana 仪表盘）
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

---

## 三、 模块一：SpringDoc OpenAPI 3 全局配置与注解深度拆解

### 1. 全局配置类（`OpenApiConfig.java`）

```java
package cn.self.studyspringc.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =====================================================================================
 * 【OpenAPI 3 全局安全与元数据配置】
 * =====================================================================================
 * 
 * 1. 在 Swagger UI 界面右上角增加 "Authorize" 锁图标按钮。
 * 2. 输入 JWT Bearer Token 后，后续在网页上调试所有受保护接口时，Swagger 会自动在请求头带上 Authorization！
 * =====================================================================================
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                // 1. 设置接口文档基本信息
                .info(new Info()
                        .title("图书管理系统 API 开放平台文档")
                        .version("v1.0.0")
                        .description("基于 Spring Boot 3/4 + Java 21 + MyBatis-Plus 构建的企业级标准规范 RESTful 接口体系")
                        .contact(new Contact()
                                .name("架构研发团队")
                                .email("architecture@company.com")
                                .url("https://github.com/self/study-spring-c"))
                        .license(new License().name("Apache 2.0").url("https://spring.io")))
                // 2. 声明全局安全校验方案 (JWT Bearer)
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("请输入在 /api/auth/login 接口获取到的 JWT Token")));
    }
}
```

### 2. DTO 与 Controller 规范化注解实战

#### DTO 标注 `@Schema`（`BookDocRequest.java`）：

```java
package cn.self.studyspringc.book.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "图书创建/更新请求参数体")
public class BookDocRequest {

    @Schema(description = "图书标题", example = "Spring Boot 核心编程与实战", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "图书标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    @Schema(description = "作者姓名", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "作者姓名不能为空")
    @Size(max = 50, message = "作者姓名不能超过 50 个字符")
    private String author;

    @Schema(description = "图书分类", example = "TECH", allowableValues = {"TECH", "LITERATURE", "SCIENCE"})
    private String category;

    @Schema(description = "库存数量", example = "100", minimum = "0")
    @Positive(message = "库存数量必须大于 0")
    private Integer stock;
}
```

#### Controller 标注 `@Tag` 与 `@Operation`（`BookDocController.java`）：

```java
package cn.self.studyspringc.book.controller;

import cn.self.studyspringc.book.dto.BookDocRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "图书管理模块", description = "提供图书的新增、修改、按ID查询、全量列表与删除等全部生命周期接口")
@Validated
@RestController
@RequestMapping("/api/v2/books")
@RequiredArgsConstructor
public class BookDocController {

    private final BookService bookService;

    @Operation(summary = "按 ID 查询单本图书详情", description = "根据路径参数主键 ID 查询，若数据不存在将返回 404 RFC 7807 错误体")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "404", description = "指定 ID 的图书不存在"),
            @ApiResponse(responseCode = "400", description = "路径参数 ID 小于等于 0")
    })
    @GetMapping("/{id}")
    public BookResponse getById(
            @Parameter(description = "图书主键 ID (必须 > 0)", example = "1", required = true)
            @Positive(message = "ID 必须为正整数")
            @PathVariable Long id
    ) {
        return bookService.get(id);
    }
}
```

---

## 四、 模块二：Spring Boot Actuator 生产监控与指标打点

### 1. `application.yaml` 监控与端口隔离配置

在生产环境中，**严禁将敏感的 Actuator 端口直接暴露给公网**。最佳实践是使用**独立管理端口**（如 8081）并限制内网访问：

```yaml
management:
  server:
    port: 8081 # 运维监控专属端口（不同于业务端口 8080）
  endpoints:
    web:
      base-path: "/actuator"
      exposure:
        # 暴露常用健康与指标端点
        include: "health,info,metrics,prometheus,env"
  endpoint:
    health:
      show-details: always # 显示详细的磁盘、数据库、Redis 连接状态
      probes:
        enabled: true # 开启 Kubernetes Liveness (存活) & Readiness (就绪) 探针
```

### 2. 自定义健康指示器（`CustomServiceHealthIndicator.java`）

```java
package cn.self.studyspringc.common.actuator;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 自定义健康检查探针：自动嵌入 /actuator/health 响应体中
 */
@Component
public class CustomServiceHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // 模拟检测外部依赖服务（例如支付网关或第三方 API）连通性
        boolean isExternalApiAvailable = checkExternalApiHealth();

        if (isExternalApiAvailable) {
            return Health.up()
                    .withDetail("externalPaymentGateway", "CONNECTED")
                    .withDetail("responseTimeMs", 28)
                    .build();
        } else {
            return Health.down()
                    .withDetail("externalPaymentGateway", "UNREACHABLE")
                    .withDetail("error", "连接第三方服务超时")
                    .build();
        }
    }

    private boolean checkExternalApiHealth() {
        return true; // 模拟健康
    }
}
```

### 3. 自定义业务指标埋点（Micrometer `MeterRegistry`）

```java
package cn.self.studyspringc.common.actuator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * =====================================================================================
 * 【Micrometer 核心指标类型】
 * 1. Counter (计数器): 只增不减的单调递增指标（如累计订单量、累计 HTTP 500 次数）。
 * 2. Gauge (仪表盘): 瞬时可增可减的值（如当前在线人数、当前队列待处理任务数）。
 * 3. Timer (计时器): 统计事件发生频率及耗时分布（P99, P95, 平均耗时）。
 * =====================================================================================
 */
@Component
public class BusinessMetricsService {

    private final Counter bookCreatedCounter;
    private final Timer orderProcessTimer;

    public BusinessMetricsService(MeterRegistry registry) {
        // 1. 注册图书创建计数器，附带 tag 标签便于在 Grafana 聚合下钻
        this.bookCreatedCounter = Counter.builder("business.book.created.total")
                .description("平台累计上架图书总量")
                .tag("environment", "prod")
                .register(registry);

        // 2. 注册订单耗时统计 Timer
        this.orderProcessTimer = Timer.builder("business.order.process.time")
                .description("图书下单核心业务处理耗时")
                .register(registry);
    }

    public void recordBookCreation() {
        bookCreatedCounter.increment();
    }

    public void recordOrderExecutionTime(long costMillis) {
        orderProcessTimer.record(costMillis, TimeUnit.MILLISECONDS);
    }
}
```

---

## 五、 实战访问与运维验证

### 1. 访问 Swagger UI 在线接口文档
启动项目后，在浏览器直接访问：
`http://localhost:8080/swagger-ui.html`
* 可以看到所有的 Controller 分组、方法描述、入参 JSON 示例，并且可以直接点击 **"Try it out"** 进行实时在线接口调试！

### 2. 访问 Actuator 监控端点
* **健康检查状态**：`curl http://localhost:8081/actuator/health`
  ```json
  {
    "status": "UP",
    "components": {
      "db": {"status": "UP", "details": {"database": "MySQL", "validationQuery": "isValid()"}},
      "redis": {"status": "UP", "details": {"version": "7.4.0"}},
      "customService": {"status": "UP", "details": {"externalPaymentGateway": "CONNECTED"}}
    }
  }
  ```
* **Prometheus 指标抓取**：`curl http://localhost:8081/actuator/prometheus`
  ```text
  # HELP business_book_created_total 平台累计上架图书总量
  # TYPE business_book_created_total counter
  business_book_created_total{environment="prod"} 42.0
  ```

---

## 六、 总结与全套 8 份进阶文档清单

恭喜！到这里你已经掌握了 Spring Boot 进阶路上的全部 8 大核心支柱：

1. [`01-mybatis-plus-persistence.md`](./01-mybatis-plus-persistence.md)：国内企业级持久层首选，动态 SQL、LambdaWrapper、分页与拦截器。
2. [`02-security-jwt-authentication.md`](./02-security-jwt-authentication.md)：Spring Security 6 + JWT 无状态安全认证、过滤器链与权限隔离。
3. [`03-aop-interceptor-filters.md`](./03-aop-interceptor-filters.md)：AOP 操作日志、接口防重复提交、HandlerInterceptor 与 MDC 全链路 TraceId。
4. [`04-spring-cache-redisson.md`](./04-spring-cache-redisson.md)：Spring Cache 声明式缓存 + Redisson 分布式锁与缓存三灾防御。
5. [`05-async-scheduling-events.md`](./05-async-scheduling-events.md)：@Async 自定义线程池、@Scheduled 定时任务、Spring Event 领域事件解耦。
6. [`06-profiles-configuration-properties.md`](./06-profiles-configuration-properties.md)：多环境隔离体系与 @ConfigurationProperties 强类型启动自检。
7. [`07-message-queue-rabbitmq.md`](./07-message-queue-rabbitmq.md)：RabbitMQ 生产者可靠发送、消费者手动 ACK、死信队列与消费幂等。
8. [`08-actuator-springdoc-observability.md`](./08-actuator-springdoc-observability.md)：Swagger 3 接口文档与 Actuator/Prometheus 生产级可观测性。
