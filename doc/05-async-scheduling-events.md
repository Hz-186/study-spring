# 05. 异步任务、定时调度与事件驱动：@Async 线程池 + @Scheduled + Spring Event

> **模块定位**：高吞吐异步处理与领域事件驱动解耦层  
> **核心技术栈**：Spring @Async (ThreadPoolTaskExecutor) / Spring Task Scheduling (Cron) / Spring ApplicationEvent / @TransactionalEventListener  
> **学习目标**：掌握生产级线程池参数设计与拒绝策略；彻底吃透 `@Async` 异步执行与 `CompletableFuture` 协同；掌握 `@Scheduled` 定时任务 Cron 语法与执行机制；掌握基于 Spring Event 的业务解耦模式，重点搞懂事务提交后监听（`AFTER_COMMIT`）的避坑原理。

---

## 一、 为什么企业级系统必须补充异步、定时与事件机制？

1. **响应耗时瓶颈（吞吐量）**：用户在 Web 页面点击“购买图书/注册账号”时，如果主线程串行执行“写库(50ms) -> 发送短信(800ms) -> 推送微信(500ms) -> 赠送积分(200ms)”，接口响应将超过 1.5 秒。借助 **`@Async` 异步线程池**，主线程 50ms 写库后直接响应成功，其余辅助逻辑后台并行执行。
2. **批处理与数据巡检（定时调度）**：每天凌晨 2:00 清理过期的临时验证码、定时核对对账单、计算每日热卖图书排行，需要使用 **`@Scheduled`** 进行 Cron 定时调度。
3. **业务强耦合毒瘤（事件驱动解耦）**：若在 `OrderService` 中直接注入 `SmsService`、`PointsService`、`CouponService`、`EmailService`，代码将变成脆弱的“大泥球”。使用 **Spring Event 发布-订阅模式**，`OrderService` 仅发布一个 `OrderCreatedEvent`，各业务订阅方独立监听，实现彻底的开闭原则（OCP）。

---

## 二、 模块一：`@Async` 异步调用与生产级自定义线程池

### 1. 为什么绝不能使用 Spring 默认的 `@Async`？
Spring 默认的异步执行器是 `SimpleAsyncTaskExecutor`，**它不会复用线程**，每次调用都会直接 `new Thread()`，在高并发请求下会在短时间内耗尽系统线程资源，直接导致服务器崩溃抛出 `OutOfMemoryError: unable to create native thread`！**在任何生产项目中，必须显式定义专属线程池！**

### 2. 自定义线程池配置类（`ThreadPoolConfig.java`）

```java
package cn.self.studyspringc.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * =====================================================================================
 * 【生产级自定义线程池参数配置与拒绝策略剖析】
 * =====================================================================================
 * 
 * 1. corePoolSize (核心线程数): 
 *    - 无论空闲与否，始终保活在池中的线程数。
 *    - CPU 密集型任务推荐：CPU核心数 + 1；IO 密集型任务（网络/DB）：CPU核心数 * 2 到 * 4。
 * 
 * 2. maxPoolSize (最大线程数): 
 *    - 当任务队列排满后，线程池允许扩容到的最大线程上限。
 * 
 * 3. queueCapacity (阻塞队列容量): 
 *    - 核心线程忙碌时，新任务进入 LinkedBlockingQueue 缓冲等待。必须设置有界队列，严禁 Integer.MAX_VALUE！
 * 
 * 4. keepAliveSeconds (非核心线程空闲存活时间): 
 *    - 超过核心线程数的临时线程，在空闲指定秒数后自动销毁回收。
 * 
 * 5. RejectedExecutionHandler (4 大经典拒绝策略):
 *    - AbortPolicy (默认): 抛出 RejectedExecutionException 异常阻止运行。
 *    - CallerRunsPolicy (最稳妥生产推荐): 谁提交的任务谁自己执行（由调用者如 Tomcat HTTP 线程执行），降低任务提交速度。
 *    - DiscardPolicy: 静默丢弃新任务，不报错。
 *    - DiscardOldestPolicy: 丢弃队列中最老的任务，尝试重新提交当前任务。
 * =====================================================================================
 */
@Slf4j
@EnableAsync
@Configuration
public class ThreadPoolConfig implements AsyncConfigurer {

    public static final String ASYNC_EXECUTOR_NAME = "customAsyncExecutor";

    @Bean(name = ASYNC_EXECUTOR_NAME)
    public ThreadPoolTaskExecutor customAsyncExecutor() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        log.info("当前服务器 CPU 核心数: {}, 正在初始化异步任务线程池...", cpuCores);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(cpuCores * 2);
        // 最大线程数
        executor.setMaxPoolSize(cpuCores * 4);
        // 有界等待队列
        executor.setQueueCapacity(500);
        // 线程空闲回收时间 (60秒)
        executor.setKeepAliveSeconds(60);
        // 线程名前缀（排查日志与 jstack 时一目了然）
        executor.setThreadNamePrefix("async-task-pool-");

        // 生产推荐：CallerRunsPolicy（调用者运行策略，提供负反馈降速）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 优雅停机配置：应用关闭时等待未完成的任务执行完毕再销毁容器
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * 实现 AsyncConfigurer 接口，指定全局默认执行器
     */
    @Override
    public Executor getAsyncExecutor() {
        return customAsyncExecutor();
    }

    /**
     * 捕获无返回值 void 异步方法抛出的未受检异常
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) -> {
            log.error("异步执行方法 [{}] 发生未捕获异常, 入参: {}, 错误信息: {}",
                    method.getName(), Arrays.toString(params), ex.getMessage(), ex);
        };
    }
}
```

### 3. 异步服务实战（`NotificationAsyncService.java`）

```java
package cn.self.studyspringc.common.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class NotificationAsyncService {

    /**
     * 场景 1：无需返回值的火发即忘 (Fire-and-Forget) 异步任务
     */
    @Async("customAsyncExecutor")
    public void sendEmailNotice(String email, String message) {
        log.info("--> [异步开始] 线程 [{}] 正在向 [{}] 发送邮件...", Thread.currentThread().getName(), email);
        try {
            Thread.sleep(1500); // 模拟耗时网络 IO
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("<-- [异步完成] 邮件已成功送达 [{}]", email);
    }

    /**
     * 场景 2：带返回值的异步任务（使用 Java 8+ CompletableFuture 组合结果）
     */
    @Async("customAsyncExecutor")
    public CompletableFuture<String> generateBookSummaryReport(Long bookId) {
        log.info("--> [异步计算] 线程 [{}] 正在分析书籍 ID: {} 的历史阅读报表...", Thread.currentThread().getName(), bookId);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String reportResult = "书籍 " + bookId + " 分析报表: 综合评分 9.8，热度指数 A+";
        log.info("<-- [异步计算完成] 报表生成完毕");
        return CompletableFuture.completedFuture(reportResult);
    }
}
```

---

## 三、 模块二：`@Scheduled` 定时调度任务

### 1. 开启定时任务并配置 Cron 表达式

在 `application.yaml` 或主类开启配置：

```java
package cn.self.studyspringc.common.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * =====================================================================================
 * 【Spring Cron 表达式 6 位语法完全指南】
 * =====================================================================================
 * 格式：秒 分 时 日 月 周
 * 示例：
 * - "0/5 * * * * ?"       : 每隔 5 秒执行一次
 * - "0 0/10 * * * ?"      : 每隔 10 分钟执行一次
 * - "0 0 2 * * ?"         : 每天凌晨 2:00:00 准时执行
 * - "0 0 12 ? * MON-FRI"  : 每周一至周五中午 12:00 执行
 * - "0 0 23 L * ?"        : 每月最后一天晚上 23:00 执行
 * 
 * 核心符号含义：
 * - * (所有可能值)
 * - ? (不指定值，仅用于“日”和“周”，避免冲突)
 * - - (区间范围，如 1-5)
 * - / (步长递增，如 0/10 表示从0开始每10递增)
 * =====================================================================================
 */
@Slf4j
@Component
@EnableScheduling
public class BookScheduledTasks {

    /**
     * 定时任务 1：基于 Cron 表达式（每天凌晨 03:00 自动清理垃圾缓存）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredTokens() {
        log.info("[定时任务 - Cron] 触发凌晨数据巡检与清理, 时间: {}", LocalDateTime.now());
    }

    /**
     * 定时任务 2：固定速率 (fixedRate)
     * 说明：每隔 60 秒触发一次，不受上次任务耗时影响（只要到点就尝试触发）。
     */
    @Scheduled(fixedRate = 60000)
    public void syncViewCountsToDatabase() {
        log.info("[定时任务 - FixedRate] 同步 Redis 浏览量至持久化 MySQL, 时间: {}", LocalDateTime.now());
    }

    /**
     * 定时任务 3：固定延迟 (fixedDelay)
     * 说明：在上一次任务【完全执行结束】之后，再等待 30 秒才开启下一次任务。
     */
    @Scheduled(initialDelay = 10000, fixedDelay = 30000)
    public void healthCheckExternalApis() {
        log.debug("[定时任务 - FixedDelay] 探测外部第三方 API 连通性状态...");
    }
}
```

---

## 四、 模块三：Spring Event 业务事件解耦（观察者模式）

### 1. 定义业务事件对象（`BookCreatedEvent.java`）

```java
package cn.self.studyspringc.book.event;

/**
 * 图书创建领域事件（标准不可变 POJO 或 Java Record）
 */
public record BookCreatedEvent(
        Long bookId,
        String title,
        String author,
        String creatorUsername
) {
}
```

### 2. 在业务 Service 中发布事件（`BookService.java` 增强）

```java
package cn.self.studyspringc.book.service;

import cn.self.studyspringc.book.dto.BookRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.entity.Book;
import cn.self.studyspringc.book.event.BookCreatedEvent;
import cn.self.studyspringc.book.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookEventPublishService {

    private final BookRepository bookRepository;
    // 注入 Spring 内置事件发布器
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public BookResponse createBookWithEvent(BookRequest request, String currentUsername) {
        // 1. 核心业务：保存图书到数据库
        Book book = new Book(request.getTitle(), request.getAuthor());
        Book saved = bookRepository.save(book);
        log.info("图书保存数据库成功，主键 ID: {}", saved.getId());

        // 2. 解耦发布领域事件：当前 Service 无需关心后续谁来发邮件、谁来计算积分！
        BookCreatedEvent event = new BookCreatedEvent(saved.getId(), saved.getTitle(), saved.getAuthor(), currentUsername);
        eventPublisher.publishEvent(event);
        log.info("成功广播图书创建事件: {}", event);

        return BookResponse.from(saved);
    }
}
```

### 3. 关键机制：`@TransactionalEventListener` 事务绑定监听

```java
package cn.self.studyspringc.book.listener;

import cn.self.studyspringc.book.event.BookCreatedEvent;
import cn.self.studyspringc.common.async.NotificationAsyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * =====================================================================================
 * 【@TransactionalEventListener 深度避坑解析】
 * =====================================================================================
 * 
 * 1. 经典生产 Bug：
 *    - 若使用普通的 @EventListener，当业务方法刚执行到 publishEvent 时，监听器立即开始发邮件/调用第三方接口；
 *    - 但随后业务方法抛出异常导致数据库回滚事务（Rollback）！此时数据库中并无该数据，但用户却收到了短信，导致数据不一致。
 * 
 * 2. 解决方案：
 *    - 使用 @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)！
 *    - 只有当外层数据库事务【真正 COMMIT 成功后】，Spring 才会触发调用该监听方法；若事务回滚，事件自动被废弃！
 * 
 * 3. 结合 @Async：
 *    - 监听器方法上加上 @Async，让监听逻辑脱离主 HTTP 请求线程，在后台自定义线程池中无阻塞执行。
 * =====================================================================================
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookNotificationListener {

    private final NotificationAsyncService notificationAsyncService;

    @Async("customAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookCreated(BookCreatedEvent event) {
        log.info("--> [事件监听响应] 捕获到事务已提交的图书创建事件: BookId=[{}], Title=[{}]", event.bookId(), event.title());

        // 触发异步邮件通知
        String noticeContent = String.format("新书《%s》已由管理员 [%s] 成功上架！", event.title(), event.creatorUsername());
        notificationAsyncService.sendEmailNotice("admin@company.com", noticeContent);
    }
}
```

---

## 五、 实战验证与测试

```java
@SpringBootTest
class AsyncAndEventTest {

    @Autowired
    private BookEventPublishService bookEventPublishService;

    @Test
    void testEventFlow() throws Exception {
        BookRequest request = new BookRequest();
        request.setTitle("微服务架构设计模式");
        request.setAuthor("Chris Richardson");

        System.out.println("====== [主线程开始调用] ======");
        bookEventPublishService.createBookWithEvent(request, "zhang_san");
        System.out.println("====== [主线程执行完毕，主流程未被耗时发送阻塞] ======");

        // 等待异步线程池执行完成
        Thread.sleep(3000);
    }
}
```

---

👉 **下一篇推荐学习**：[06. 配置管理与多环境隔离：Profiles + @ConfigurationProperties 强类型绑定](./06-profiles-configuration-properties.md)
