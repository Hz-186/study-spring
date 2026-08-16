# 06. 配置管理与多环境隔离：Profiles + @ConfigurationProperties 强类型绑定

> **模块定位**：工程化配置架构与多环境交付层  
> **核心技术栈**：Spring Profiles / @ConfigurationProperties / Jakarta Validation / Duration & DataSize  
> **学习目标**：告别零散混乱的 `@Value` 硬编码与单环境配置文件；掌握企业级多环境（dev/test/prod）隔离体系；掌握基于 `@ConfigurationProperties` 的类型安全强类型配置类设计、嵌套属性绑定、集合映射，以及结合 `@Validated` 实现服务启动时的配置自检防崩机制。

---

## 一、 为什么企业级系统必须摒弃硬编码 `@Value`？

在初学 Spring 时，很多开发者习惯在各个类中写 `@Value("${app.upload.path}") private String path;`。在大型企业级项目中，这种做法存在严重弊端：
1. **重构脆弱**：若 YAML 中的配置项名称修改，所有散落在各个类的 `@Value` 字符串不会报编译错误，直到运行到该行代码才会突然抛出异常。
2. **缺乏类型与范围校验**：例如一个“超时时间”配置，传了 `-10` 或非法字符串，代码无法在应用启动时提前捕获。
3. **不支持复杂层次与结构**：无法优雅地将包含多层嵌套对象（如 `Map<String, ClientEndpoint>`、`List<String>`、`Duration`、`DataSize`）的复杂配置树一次性结构化注入。

通过 **`@ConfigurationProperties` 强类型配置绑定**，可以实现 IDE 代码自动补全提示、强类型转换、层次化组织与启动期健康自检。

---

## 二、 多环境 Profiles 隔离体系设计

```text
src/main/resources/
├── application.yaml        # [核心主配置]：公共通用配置、指定当前激活的环境 Profile
├── application-dev.yaml    # [开发环境]：本地 localhost MySQL/Redis、开启控制台 SQL 日志、长超时
├── application-test.yaml   # [测试环境]：联调测试服务器数据源
└── application-prod.yaml   # [生产环境]：集群数据源、关闭 SQL 打印、敏感密码必须通过环境变量注入
```

### 1. 主配置文件（`src/main/resources/application.yaml`）

```yaml
spring:
  application:
    name: study-spring-c
  profiles:
    # 默认激活开发环境（在 IDEA 本地启动时生效）
    # 生产部署时通过命令行参数切换：java -jar app.jar --spring.profiles.active=prod
    active: ${SPRING_PROFILES_ACTIVE:dev}

# 全局通用业务属性根节点
app:
  jwt:
    secret-key: ${JWT_SECRET:DefaultLocalDevSecretKeyMustBe32CharactersOrLonger!}
    expiration-ms: 604800000 # 7天
  security:
    white-list-paths:
      - /api/auth/**
      - /swagger-ui/**
      - /v3/api-docs/**
      - /actuator/health
```

### 2. 开发环境配置（`src/main/resources/application-dev.yaml`）

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/study_db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: root
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true

app:
  file-storage:
    upload-dir: "/tmp/study-spring-uploads"
    max-file-size: 10MB
    connect-timeout: 3s
  notification:
    enabled: true
    recipients:
      - dev-admin@company.com
      - dev-team@company.com
```

### 3. 生产环境配置（`src/main/resources/application-prod.yaml`）

```yaml
server:
  port: 8080
  # 开启 Gzip 压缩，优化网络带宽
  compression:
    enabled: true
    mime-types: application/json,text/html,text/plain

spring:
  datasource:
    # 生产环境强制从宿主机环境变量读取数据库连接与凭据，禁止在代码库明文提交！
    url: ${PROD_DB_URL}
    username: ${PROD_DB_USERNAME}
    password: ${PROD_DB_PASSWORD}
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
  jpa:
    show-sql: false # 生产严禁打印 SQL，防止磁盘日志被打爆

app:
  file-storage:
    upload-dir: ${PROD_STORAGE_PATH:/data/uploads}
    max-file-size: 50MB
    connect-timeout: 5s
  notification:
    enabled: true
    recipients:
      - on-call-ops@company.com
```

---

## 三、 强类型配置类设计与启动校验（`AppProperties.java`）

```java
package cn.self.studyspringc.common.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================================
 * 【@ConfigurationProperties 强类型属性绑定核心机制】
 * =====================================================================================
 * 
 * 1. prefix = "app":
 *    - 匹配 YAML 文件中以 `app` 开头的所有子配置树。
 *    - 支持宽松绑定（Relaxed Binding）：无论是 `upload-dir`、`upload_dir` 还是 `uploadDir`，均能自动映射至属性 `uploadDir`。
 * 
 * 2. @Validated + Jakarta Validation 启动期自检：
 *    - 在配置类上加上 @Validated，当 Spring Boot 容器启动初始化该 Bean 时，
 *      会自动检查所有字段约束（如 @NotBlank, @NotNull, @Email）。
 *    - 若配置缺失或非法，应用在启动阶段就会直接【快速失败（Fail-Fast）】并清晰打印是哪个配置项出错，
 *      彻底避免上线后运行时才发生 NullPointerException！
 * 
 * 3. 强类型高级特性：
 *    - Duration: 自动解析 "3s", "500ms", "10m" 为 Java 8 java.time.Duration 对象。
 *    - DataSize: 自动解析 "10MB", "500KB", "2GB" 为 org.springframework.util.unit.DataSize 对象。
 * =====================================================================================
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @Valid
    private final Jwt jwt = new Jwt();

    @Valid
    private final Security security = new Security();

    @Valid
    private final FileStorage fileStorage = new FileStorage();

    @Valid
    private final Notification notification = new Notification();

    /**
     * 演示 Map 动态服务节点映射配置
     */
    private Map<String, ThirdPartyEndpoint> externalServices = new HashMap<>();

    @Data
    public static class Jwt {
        @NotBlank(message = "app.jwt.secret-key 密钥不能为空且长度必须大于32位")
        private String secretKey;

        @NotNull(message = "app.jwt.expiration-ms 过期时间不能为空")
        private Long expirationMs;
    }

    @Data
    public static class Security {
        @NotEmpty(message = "安全拦截白名单列表不能为空")
        private List<String> whiteListPaths = new ArrayList<>();
    }

    @Data
    public static class FileStorage {
        @NotBlank(message = "文件上传根目录 upload-dir 不能为空")
        private String uploadDir;

        /**
         * 自动转换文件大小单位，默认单位为 MB
         */
        @DataSizeUnit(DataUnit.MEGABYTES)
        private DataSize maxFileSize = DataSize.ofMegabytes(10);

        /**
         * 自动转换时间单位，默认单位为 SECONDS
         */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration connectTimeout = Duration.ofSeconds(3);
    }

    @Data
    public static class Notification {
        private boolean enabled = true;

        @NotEmpty(message = "通知接收者邮箱列表不能为空")
        private List<@Email(message = "通知收件人邮箱格式不合法") String> recipients = new ArrayList<>();
    }

    @Data
    public static class ThirdPartyEndpoint {
        @NotBlank
        private String baseUrl;
        private Duration readTimeout = Duration.ofSeconds(5);
    }
}
```

---

## 四、 激活配置类扫描（`BookApiApplication.java`）

在主启动类或者配置类上使用 `@ConfigurationPropertiesScan` 开启自动扫描装配：

```java
package cn.self.studyspringc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("cn.self.studyspringc.common.config.properties")
public class BookApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookApiApplication.class, args);
    }
}
```

---

## 五、 在业务代码中优雅注入并使用配置 Bean

告别零散的 `@Value`，直接像注入普通 Service 一样注入 `AppProperties`：

```java
package cn.self.studyspringc.book.service;

import cn.self.studyspringc.common.config.properties.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    // 直接通过构造器注入强类型配置对象
    private final AppProperties appProperties;

    public void printConfigSummary() {
        AppProperties.FileStorage storage = appProperties.getFileStorage();
        log.info("当前文件存储目录: {}", storage.getUploadDir());
        log.info("单文件大小限制: {} MB", storage.getMaxFileSize().toMegabytes());
        log.info("网络连接超时: {} 秒", storage.getConnectTimeout().toSeconds());
        log.info("白名单路径列表: {}", appProperties.getSecurity().getWhiteListPaths());
        log.info("邮件通知接收人: {}", appProperties.getNotification().getRecipients());
    }
}
```

---

## 六、 Spring Boot 配置加载优先级全景法则

当同一个配置属性（例如 `server.port`）在不同地方被定义时，Spring Boot 遵循严格的**自顶向下高优先级覆盖低优先级**法则：

```text
1. 命令行参数 (最高优先级，生产常用)
   例如: java -jar app.jar --server.port=9090 --spring.profiles.active=prod
   
2. 操作系统环境变量 (Docker / Kubernetes 容器编排最常用)
   例如: export PROD_DB_PASSWORD="MySecurePassword123"
   
3. 外部 jar 包同级目录下的 config/ 配置文件
   例如: ./config/application.yaml
   
4. 外部 jar 包同级目录下的配置文件
   例如: ./application.yaml
   
5. 项目内部 classpath 路径下的环境特定配置文件
   例如: classpath:/application-prod.yaml
   
6. 项目内部 classpath 路径下的主配置文件 (最低优先级，默认基线)
   例如: classpath:/application.yaml
```

---

## 七、 实战演练与测试自测

```java
@SpringBootTest
class AppPropertiesTest {

    @Autowired
    private AppProperties appProperties;

    @Test
    void testPropertiesBinding() {
        Assertions.assertNotNull(appProperties.getJwt().getSecretKey());
        Assertions.assertNotNull(appProperties.getFileStorage().getUploadDir());
        System.out.println("成功加载配置: " + appProperties);
    }
}
```

---

👉 **下一篇推荐学习**：[07. 消息中间件实战：RabbitMQ 生产者、消费者 ACK、死信队列与可靠投递](./07-message-queue-rabbitmq.md)
