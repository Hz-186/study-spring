# 04. 进阶缓存与分布式锁：Spring Cache 声明式缓存 + Redisson 实战 + 缓存三灾防御

> **模块定位**：高性能缓存与分布式高并发控制层  
> **核心技术栈**：Spring Cache / Redis 7.x / Redisson 3.x / Jackson 序列化  
> **学习目标**：掌握生产级缓存架构设计，摆脱代码硬编码 Redis 的低效方式，学会使用 Spring Cache 声明式注解实现自动读写缓存与失效；掌握基于 Redisson 的分布式锁解决超卖与高并发数据冲突；掌握缓存穿透、击穿、雪崩的标准工业级防御方案。

---

## 一、 为什么必须升级到 Spring Cache + Redisson？

当前 `study-spring-c` 项目中仅在 `BookViewService` 中使用了基础的 `StringRedisTemplate`。在实际大厂和工业级高并发系统中存在两个瓶颈：
1. **侵入性过强**：如果在每个 Service 的查询方法中都手写“先查 Redis -> 为空查 DB -> 写入 Redis”，业务逻辑将被大量的缓存胶水代码污染。通过 **Spring Cache 声明式注解（`@Cacheable`）**，只需一个注解即可由底层 AOP 自动完成缓存命中与回写。
2. **并发安全与集群死锁**：在分布式部署多实例下，单机 JVM 的 `synchronized` 和 `ReentrantLock` 彻底失效。原生 Redis 的 `SETNX` 容易因宕机导致死锁或因执行超时发生锁被误删。**Redisson** 提供了成熟的看门狗（Watchdog）自动续期与原子分布式锁。

---

## 二、 依赖引入配置（`build.gradle`）

```groovy
dependencies {
    // Spring Cache 核心抽象
    implementation 'org.springframework.boot:spring-boot-starter-cache'
    
    // Spring Data Redis
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    
    // Redisson 官方针对 Spring Boot 3 的 Starter
    implementation 'org.redisson:redisson-spring-boot-starter:3.31.0'
}
```

---

## 三、 自定义 Redis 缓存配置（解决序列化乱码与 TTL）

Spring Cache 默认使用 JDK 序列化（存入 Redis 会变成 `\xac\xed\x00\x05` 开头的可读性极差的二进制乱码），且默认永不过期。我们必须自定义配置 JSON 序列化器并配置默认过期时间。

```java
package cn.self.studyspringc.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LBasicDefaultTyping;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * =====================================================================================
 * 【Spring Cache + Redis 深度定制配置】
 * =====================================================================================
 * 
 * 1. @EnableCaching: 开启 Spring 声明式缓存驱动支持。
 * 2. Key 序列化: 使用 StringRedisSerializer 保证 Key 为可读字符串（如 "books::1"）。
 * 3. Value 序列化: 使用 GenericJackson2JsonRedisSerializer 自动将 Java 对象转为 JSON 字符串存储。
 * 4. disableCachingNullValues(): 默认关闭空值缓存；但若要防缓存穿透，则应允许缓存 null（配合短 TTL）。
 * =====================================================================================
 */
@EnableCaching
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 配置 ObjectMapper 支持 Java 8 时间类型（LocalDateTime）
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                // 默认全局缓存过期时间：30 分钟
                .entryTtl(Duration.ofMinutes(30))
                // Key 序列化器
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                // Value 序列化器（JSON 格式）
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                // 允许缓存空值（缓存 null 是防御【缓存穿透】的经典策略）
                .computePrefixWith(cacheName -> cacheName + "::");

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
```

---

## 四、 Spring Cache 核心注解与 Spring EL 表达式完全拆解

| 注解 | 作用时机 | 典型业务场景 | 核心参数语法剖析 |
| :--- | :--- | :--- | :--- |
| **`@Cacheable`** | **执行前**先查缓存；命中则直接返回，未命中才执行方法并将结果写入缓存 | 数据查询（`getById`） | `value="books"`: 缓存空间名称<br>`key="#id"`: 基于入参构建 Key（如 `books::1`）<br>`unless="#result == null"`: 结果为空时不写入缓存 |
| **`@CachePut`** | **执行后**必定调用目标方法，并将最新的返回值刷新到缓存中 | 数据修改更新（`update`） | `value="books"`, `key="#request.id"` |
| **`@CacheEvict`** | **执行后**从 Redis 中删除指定的缓存 Key，保证一致性 | 数据删除或批量清理（`delete`） | `value="books"`, `key="#id"`<br>`allEntries=true`: 清空该分区下所有缓存 |

### 业务实战代码示范（`BookCacheService.java`）：

```java
package cn.self.studyspringc.book.service;

import cn.self.studyspringc.book.dto.BookRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.entity.Book;
import cn.self.studyspringc.book.repository.BookRepository;
import cn.self.studyspringc.common.exception.BookNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookCacheService {

    private final BookRepository bookRepository;

    /**
     * 【查询书籍：声明式缓存】
     * 1. 首次查询：查 DB -> 写入 Redis (Key: "book::1") -> 返回数据。
     * 2. 二次查询：直接从 Redis 读取并反序列化，控制台不会输出任何 SQL，耗时 < 2ms！
     * 3. Spring EL 语法：#id 代表入参 id 的值；unless = "#result == null" 表示查不到时不缓存。
     */
    @Cacheable(value = "book", key = "#id", unless = "#result == null")
    public BookResponse getBookById(Long id) {
        log.info("--> [命中数据库查询] 正在从 MySQL 查询图书 ID: {}", id);
        Book book = bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
        return BookResponse.from(book);
    }

    /**
     * 【更新书籍：精准淘汰缓存 (Cache-Aside 模式)】
     * 当图书信息修改成功后，立即从 Redis 中淘汰旧数据（下次查询时自动懒加载最新数据）。
     */
    @Transactional
    @CacheEvict(value = "book", key = "#id")
    public BookResponse updateBook(Long id, BookRequest request) {
        log.info("--> [淘汰缓存] 正在更新数据库并清除 Redis 缓存 ID: {}", id);
        Book book = bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
        book.update(request.getTitle(), request.getAuthor());
        return BookResponse.from(book);
    }

    /**
     * 【删除书籍：清除缓存】
     */
    @Transactional
    @CacheEvict(value = "book", key = "#id")
    public void deleteBook(Long id) {
        log.info("--> [删除数据并淘汰缓存] ID: {}", id);
        Book book = bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
        bookRepository.delete(book);
    }

    /**
     * 【清空全部分区缓存】
     * 用于后台批量导入或大促活动前刷新全量缓存。
     */
    @CacheEvict(value = "book", allEntries = true)
    public void clearAllBookCache() {
        log.info("--> [清空全量图书缓存]");
    }
}
```

---

## 五、 Redisson 分布式锁核心实战

### 1. 为什么选择 Redisson 看门狗机制？

* **死锁痛点**：若设置锁超时为 5 秒，但业务偶发 GC 或耗时 8 秒，则在第 5 秒锁被自动释放，其他线程并发冲入引发数据混乱；且原线程执行完毕后会误删其他线程持有的新锁。
* **Redisson 看门狗（Watchdog）**：
  * 加锁时若不显式指定 `leaseTime`，默认开启 30 秒看门狗；
  * 后台守护线程每隔 `30 / 3 = 10 秒` 自动检查一次，若业务线程仍在运行，自动将锁超时时间续期重置为 30 秒；
  * 当业务线程执行完毕调用 `unlock()` 或服务进程宕机崩溃时，看门狗自动停止，锁安全自然过期。

### 2. Redisson 秒杀扣库存实战（`BookStockService.java`）

```java
package cn.self.studyspringc.book.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁控制并发秒杀与库存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookStockService {

    private static final String LOCK_PREFIX = "lock:book:stock:";
    private final RedissonClient redissonClient;

    /**
     * 高并发秒杀下单 / 安全扣减库存
     * 
     * @param bookId 书籍 ID
     * @param buyQuantity 购买数量
     * @return 是否购买成功
     */
    public boolean seckillBook(Long bookId, int buyQuantity) {
        String lockKey = LOCK_PREFIX + bookId;
        
        // 1. 获取公平/可重入分布式锁对象
        RLock rLock = redissonClient.getLock(lockKey);

        boolean isLocked = false;
        try {
            // 2. 尝试加锁：最多等待 3 秒；加锁成功后不传 leaseTime 则自动启用看门狗续期机制！
            isLocked = rLock.tryLock(3, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("抢购火爆，获取分布式锁超时: bookId={}", bookId);
                return false;
            }

            log.info("线程 [{}] 成功获取分布式锁，开始扣减库存业务...", Thread.currentThread().getName());

            // 3. 执行核心原子临界区业务（模拟查询库存与扣减）
            // 实际项目中调用 Mapper 的原子 SQL 或更新 DB
            Thread.sleep(100); // 模拟耗时业务操作

            log.info("图书 [{}] 库存扣减成功！", bookId);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("加锁被中断异常", e);
            return false;
        } finally {
            // 4. 关键安全点：必须在 finally 中释放锁，且必须先判断当前线程是否持有该锁！
            if (isLocked && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
                log.info("线程 [{}] 成功释放分布式锁", Thread.currentThread().getName());
            }
        }
    }
}
```

---

## 六、 生产环境“缓存三大灾难”标准防御全景指南

```mermaid
graph TD
    A[高并发请求访问缓存] --> B{是否存在缓存异常?}
    
    B -->|场景 1: 缓存穿透| C[查询不存在的假 ID, 每次穿透打爆 DB]
    C --> C1[方案 A: 缓存空对象 null + 短 TTL 5分钟]
    C --> C2[方案 B: 前置布隆过滤器 BloomFilter 拦截]

    B -->|场景 2: 缓存击穿| D[某热点 Key 突然过期, 瞬间百万并发打入 DB]
    D --> D1[方案: 使用 Redisson 分布式互斥锁, 只放行 1 个线程查库回填]

    B -->|场景 3: 缓存雪崩| E[大量 Key 在同一秒集体过期, DB 压力骤增瘫痪]
    E --> E1[方案: TTL 过期时间随机加盐 (如 30分钟 + 随机 1~300秒)]
```

### 1. 缓存穿透防御代码：
* 在 `RedisCacheConfig` 中配置 `.computePrefixWith(...)` 且不要开启 `.disableCachingNullValues()`，当数据库查到 `null` 时，Redis 也会存一条标记为 `null` 的数据并设置 5 分钟短过期时间，防止黑客构造大量 `-1` 等非法 ID 击穿数据库。

### 2. 缓存雪崩防御代码：
* 在保存自定义 Key 时，TTL 加上随机偏移量：
```java
// 基础 1 小时 + 随机 0~600 秒扰动
long randomTtl = 3600 + ThreadLocalRandom.current().nextInt(600);
redisTemplate.opsForValue().set(key, jsonValue, Duration.ofSeconds(randomTtl));
```

---

👉 **下一篇推荐学习**：[05. 异步任务、定时调度与事件驱动：@Async 线程池 + @Scheduled + Spring Event](./05-async-scheduling-events.md)
