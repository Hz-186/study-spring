# 01. 国内主流持久层：MyBatis-Plus 实战与动态 SQL

> **模块定位**：数据访问与持久化层进阶  
> **核心技术栈**：MyBatis 3.x / MyBatis-Plus 3.5+ / MySQL 8.0 / Lombok  
> **学习目标**：掌握国内企业级开发最主流的 ORM 框架，彻底学会实体映射、单表极速 CRUD、动态多条件查询（XML 与 LambdaWrapper）、逻辑删除、乐观锁、自动填充与通用分页插件。

---

## 一、 为什么必须补充 MyBatis-Plus？（对比 JPA）

当前项目中使用了 **Spring Data JPA**。JPA 在简单增删改查时非常优雅，但在国内实际企业开发中，**MyBatis / MyBatis-Plus** 的市场占有率超过 80%，原因如下：

1. **复杂 SQL 掌控力**：国内互联网业务多表关联、聚合统计、报表分析极多，JPA 的 JPQL 或 Criteria API 极其臃肿晦涩；而 MyBatis 允许直接在 XML 中编写原生优化 SQL，DBA 也更容易审核与优化索引。
2. **开发效率提升**：MyBatis-Plus（简称 MP）在 MyBatis 基础上做到了“只做增强不做改变”，内置了 `BaseMapper<T>` 和 `IService<T>`，单表 CRUD 无需编写一行 SQL 或 XML，兼具 JPA 的便捷性与 MyBatis 的灵活性。
3. **强大的条件构造器**：通过 Java 8 的方法引用（如 `User::getName`）构建类型安全的查询条件，杜绝了硬编码字段名可能导致的拼写错误。

---

## 二、 依赖引入配置（`build.gradle`）

在 `build.gradle` 的 `dependencies` 块中添加 MyBatis-Plus 官方 Starter（注意：在 Spring Boot 3.x / 4.x + Java 21 环境下，使用 `mybatis-plus-spring-boot3-starter` 3.5.5+ 版本）：

```groovy
dependencies {
    // MyBatis-Plus 核心 Spring Boot 3 Starter（内置了 mybatis-spring 及核心依赖）
    implementation 'com.baomidou:mybatis-plus-spring-boot3-starter:3.5.7'
    
    // MySQL 数据库驱动
    runtimeOnly 'com.mysql:mysql-connector-j'
    
    // Lombok 简化 Getter/Setter
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

在 `application.yaml` 中配置 MyBatis-Plus：

```yaml
mybatis-plus:
  # XML 映射文件存放路径（位于 resources/mapper/ 目录下）
  mapper-locations: classpath*:/mapper/**/*.xml
  # 实体类所在包（用于 XML 中可以直接简写类名，无需写全类路径）
  type-aliases-package: cn.self.studyspringc.book.entity
  configuration:
    # 开启数据库下划线字段映射到 Java 驼峰命名（如 book_title -> bookTitle）
    map-underscore-to-camel-case: true
    # 控制台打印执行的真实 SQL 语句与参数（仅开发环境开启）
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      # 逻辑删除全局字段名（0: 未删除, 1: 已删除）
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

---

## 三、 数据库建表 DDL（MySQL 8.0）

```sql
CREATE TABLE IF NOT EXISTS `tb_book` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `title` VARCHAR(100) NOT NULL COMMENT '书籍标题',
    `author` VARCHAR(100) NOT NULL COMMENT '作者',
    `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价格',
    `category` VARCHAR(50) NOT NULL DEFAULT 'GENERAL' COMMENT '图书分类',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    `version` INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记(0:正常, 1:已删除)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_author_category` (`author`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书信息表';
```

---

## 四、 实体类定义与注解语法深度拆解

```java
package cn.self.studyspringc.book.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * =====================================================================================
 * 【MyBatis-Plus 实体类注解完全解析】
 * =====================================================================================
 * 
 * 1. @TableName("tb_book"):
 *    - 显式指定该实体类映射到数据库中的表名为 `tb_book`。
 *    - 若不加该注解，MP 默认按照类名下划线风格推断（如 BookEntity -> book_entity）。
 * 
 * 2. @TableId(type = IdType.AUTO):
 *    - 标注主键字段。
 *    - IdType.AUTO: 使用数据库自身的自增主键（MySQL AUTO_INCREMENT）。
 *    - IdType.ASSIGN_ID: MP 默认算法（雪花算法 Snowflake 生成 64 位 Long 型分布式唯一 ID）。
 * 
 * 3. @TableField:
 *    - fill = FieldFill.INSERT: 新增数据时自动触发 MetaObjectHandler 填充该字段值（创建时间）。
 *    - fill = FieldFill.INSERT_UPDATE: 新增或修改数据时均自动更新该字段值（更新时间）。
 *    - exist = false: 声明某个属性纯粹是业务临时变量，数据库表中并不存在该字段。
 * 
 * 4. @Version:
 *    - 乐观锁注解。配合 OptimisticLockerInnerInterceptor 拦截器使用。
 *    - 当执行 updateById 时，MP 会自动在 SQL 中追加 WHERE version = 当前版本，并将 version + 1。
 * 
 * 5. @TableLogic:
 *    - 逻辑删除注解。
 *    - 调用 deleteById(id) 时，底层不再执行物理 DELETE，而是执行 UPDATE tb_book SET deleted = 1 WHERE id = ?。
 *    - 所有的 select 查询语句会自动在 WHERE 条件中追加 AND deleted = 0。
 * =====================================================================================
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tb_book")
public class BookEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    @TableField("author")
    private String author;

    @TableField("price")
    private BigDecimal price;

    @TableField("category")
    private String category;

    @TableField("stock")
    private Integer stock;

    @Version
    @TableField(value = "version", fill = FieldFill.INSERT)
    private Integer version;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

---

## 五、 数据访问层（Mapper）与 XML 动态 SQL 编写

### 1. Mapper 接口定义

```java
package cn.self.studyspringc.book.mapper;

import cn.self.studyspringc.book.entity.BookEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * =====================================================================================
 * 【BaseMapper<T> 继承机制】
 * =====================================================================================
 * 继承 BaseMapper<BookEntity> 后，Spring 会通过 MyBatis 的 MapperProxyFactory 自动生成动态代理，
 * 默认立即拥有基础单表操作方法：
 * - insert(entity): 插入记录
 * - selectById(id): 按主键查询
 * - selectList(wrapper): 条件查询列表
 * - updateById(entity): 按主键更新
 * - deleteById(id): 按主键（逻辑/物理）删除
 * 
 * 若需要写多表关联或超复杂动态 SQL，则在接口中声明方法，并在对应的 XML 中编写 SQL 即可！
 * =====================================================================================
 */
@Mapper
public interface BookMapper extends BaseMapper<BookEntity> {

    /**
     * 自定义复杂条件搜索书籍（通过 XML 实现）
     * 
     * @param category 图书分类（可选）
     * @param minPrice 最低价格（可选）
     * @param maxPrice 最高价格（可选）
     * @param authorKeywords 作者关键词列表（多关键字批量匹配，可选）
     * @return 符合条件的图书列表
     */
    List<BookEntity> searchBooksByConditions(
            @Param("category") String category,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("authorKeywords") List<String> authorKeywords
    );

    /**
     * 高并发扣减库存（原子递减防超卖）
     * 
     * @param bookId 书籍 ID
     * @param quantity 扣减数量
     * @return 影响行数（>0 表示扣减成功，0 表示库存不足）
     */
    int deductStock(@Param("bookId") Long bookId, @Param("quantity") Integer quantity);
}
```

### 2. XML 映射文件（`src/main/resources/mapper/BookMapper.xml`）

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<!-- namespace 必须与 Mapper 接口的全限定类名完全一致 -->
<mapper namespace="cn.self.studyspringc.book.mapper.BookMapper">

    <!-- 通用结果映射 (ResultMap) -->
    <resultMap id="BaseResultMap" type="cn.self.studyspringc.book.entity.BookEntity">
        <id property="id" column="id"/>
        <result property="title" column="title"/>
        <result property="author" column="author"/>
        <result property="price" column="price"/>
        <result property="category" column="category"/>
        <result property="stock" column="stock"/>
        <result property="version" column="version"/>
        <result property="deleted" column="deleted"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <!-- 通用字段片段 (SQL Fragment) -->
    <sql id="Base_Column_List">
        id, title, author, price, category, stock, version, deleted, create_time, update_time
    </sql>

    <!-- 
      =================================================================================
      【动态 SQL 标签详解】
      1. <select>: 声明查询语句，resultMap 指定映射的 JavaBean 结构。
      2. <where>: 智能标签！会自动在子条件成立时加上 "WHERE"，并自动剔除多余的 "AND" 或 "OR"。
      3. <if test="表达式">: 条件判断标签。如果传入参数不为 null 且不为空串，才拼接入 SQL。
      4. <foreach>: 循环遍历集合（如 List）。
         - collection: 入参集合名称
         - item: 循环变量别名
         - open: 循环前缀（如 "("）
         - separator: 元素之间的分隔符（如 "OR" 或 ","）
         - close: 循环后缀（如 ")"）
      =================================================================================
    -->
    <select id="searchBooksByConditions" resultMap="BaseResultMap">
        SELECT 
            <include refid="Base_Column_List"/>
        FROM tb_book
        <where>
            <!-- 逻辑删除过滤 -->
            deleted = 0
            
            <!-- 分类精确匹配 -->
            <if test="category != null and category != ''">
                AND category = #{category}
            </if>
            
            <!-- 价格区间过滤 -->
            <if test="minPrice != null">
                AND price &gt;= #{minPrice}
            </if>
            <if test="maxPrice != null">
                AND price &lt;= #{maxPrice}
            </if>
            
            <!-- 多作者关键字模糊匹配: (author LIKE '%k1%' OR author LIKE '%k2%') -->
            <if test="authorKeywords != null and authorKeywords.size() > 0">
                AND
                <foreach collection="authorKeywords" item="kw" open="(" separator="OR" close=")">
                    author LIKE CONCAT('%', #{kw}, '%')
                </foreach>
            </if>
        </where>
        ORDER BY price ASC, id DESC
    </select>

    <!-- 原子性扣减库存 SQL：利用数据库行级锁，确保 stock - #{quantity} >= 0 -->
    <update id="deductStock">
        UPDATE tb_book
        SET stock = stock - #{quantity},
            update_time = NOW()
        WHERE id = #{bookId}
          AND stock &gt;= #{quantity}
          AND deleted = 0
    </update>

</mapper>
```

---

## 六、 MyBatis-Plus 核心配置类（拦截器与自动填充）

```java
package cn.self.studyspringc.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 核心插件与行为配置
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 核心拦截器链：
     * 1. 分页拦截器 (PaginationInnerInterceptor): 支持 MySQL/PostgreSQL 的物理分页（自动拼接 LIMIT / OFFSET）。
     * 2. 乐观锁拦截器 (OptimisticLockerInnerInterceptor): 支持 @Version 版本号防并发冲突。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        
        // 1. 添加分页插件 (指定数据库类型为 MySQL)
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大限制 100 条，防止恶意请求超大分页导致内存溢出 (OOM)
        paginationInterceptor.setMaxLimit(100L);
        // 溢出总页数后是否进行处理 (true: 返回首页)
        paginationInterceptor.setOverflow(false);
        interceptor.addInnerInterceptor(paginationInterceptor);

        // 2. 添加乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        
        return interceptor;
    }

    /**
     * 自动填充元数据处理器：
     * 当执行 insert 或 update 时，无需业务代码手动设置 createTime / updateTime / version，由 MP 自动注入。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                log.debug("触发 MyBatis-Plus 插入自动填充...");
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "version", Integer.class, 1);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                log.debug("触发 MyBatis-Plus 更新自动填充...");
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
```

---

## 七、 业务服务层（IService & Lambda 语法实战）

```java
package cn.self.studyspringc.book.service;

import cn.self.studyspringc.book.entity.BookEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.math.BigDecimal;
import java.util.List;

public interface IBookMpService extends IService<BookEntity> {

    /**
     * 演示 LambdaQueryWrapper：动态多条件分页查询
     */
    IPage<BookEntity> queryPageByConditions(
            int pageNum, 
            int pageSize, 
            String titleKeyword, 
            String author, 
            BigDecimal minPrice
    );

    /**
     * 演示 LambdaUpdateWrapper：局部安全更新指定字段
     */
    boolean updatePriceAndStock(Long bookId, BigDecimal newPrice, Integer addStock);

    /**
     * 扣减库存
     */
    boolean deductStock(Long bookId, Integer quantity);
}
```

### 业务实现类（`ServiceImpl`）：

```java
package cn.self.studyspringc.book.service.impl;

import cn.self.studyspringc.book.entity.BookEntity;
import cn.self.studyspringc.book.mapper.BookMapper;
import cn.self.studyspringc.book.service.IBookMpService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * =====================================================================================
 * 【ServiceImpl<Mapper, Entity> 机制与 Lambda 语法剖析】
 * =====================================================================================
 * 
 * 1. 继承 ServiceImpl<BookMapper, BookEntity>:
 *    - 自动拥有通用 CRUD 封装方法：save(), saveBatch(), getById(), list(), page(), removeById() 等。
 *    - 内置 `baseMapper` 属性，可以直接调用注入的 BookMapper。
 * 
 * 2. LambdaQueryWrapper<BookEntity> 核心语法：
 *    - .like(StringUtils.isNotBlank(title), BookEntity::getTitle, title)
 *      * 第 1 个参数（boolean condition）：条件开关！若为 false，该 SQL 条件自动不生成（无需写复杂的 if 判断）。
 *      * 第 2 个参数（SFunction<T, ?> column）：实体类方法引用（BookEntity::getTitle），编译期类型检查，绝不会打错数据库字段名。
 *      * 第 3 个参数（Object val）：查询匹配的值。
 *    - .ge(BookEntity::getPrice, minPrice): 大于等于 (Greater or Equal, >=)。
 *    - .le(...): 小于等于 (Less or Equal, <=)。
 *    - .eq(...): 等于 (Equal, =)。
 *    - .orderByDesc(BookEntity::getCreateTime): 按创建时间倒序排。
 * =====================================================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMpServiceImpl extends ServiceImpl<BookMapper, BookEntity> implements IBookMpService {

    @Override
    public IPage<BookEntity> queryPageByConditions(
            int pageNum,
            int pageSize,
            String titleKeyword,
            String author,
            BigDecimal minPrice
    ) {
        // 1. 构建分页参数对象（页码从 1 开始，每页条数）
        Page<BookEntity> pageParam = new Page<>(pageNum, pageSize);

        // 2. 构建链式 Lambda 条件构造器
        LambdaQueryWrapper<BookEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                // 当 titleKeyword 不为空时，追加 LIKE %keyword%
                .like(StringUtils.isNotBlank(titleKeyword), BookEntity::getTitle, titleKeyword)
                // 当 author 不为空时，追加 author = ?
                .eq(StringUtils.isNotBlank(author), BookEntity::getAuthor, author)
                // 当 minPrice 不为空时，追加 price >= ?
                .ge(minPrice != null, BookEntity::getPrice, minPrice)
                // 默认按 ID 降序排列
                .orderByDesc(BookEntity::getId);

        // 3. 执行物理分页查询（底层自动执行 SELECT COUNT(*) 统计总数 + SELECT ... LIMIT ?, ?）
        return this.page(pageParam, queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePriceAndStock(Long bookId, BigDecimal newPrice, Integer addStock) {
        // 使用 LambdaUpdateWrapper 仅更新指定列，无需先查询整行 Entity
        LambdaUpdateWrapper<BookEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper
                .eq(BookEntity::getId, bookId)
                .set(newPrice != null, BookEntity::getPrice, newPrice)
                // 支持直接执行 SQL 表达式（库存累加）
                .setSql(addStock != null && addStock > 0, "stock = stock + " + addStock);

        return this.update(updateWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long bookId, Integer quantity) {
        int affectedRows = baseMapper.deductStock(bookId, quantity);
        if (affectedRows <= 0) {
            log.warn("扣减书籍 [{}] 库存失败，库存不足或书籍不存在", bookId);
            return false;
        }
        return true;
    }
}
```

---

## 八、 实战演练与落地自测

### 1. 落地步骤
1. 修改 `build.gradle` 引入 `mybatis-plus-spring-boot3-starter`。
2. 在本地 MySQL 执行 DDL 语句创建 `tb_book` 表。
3. 创建 `BookEntity`、`BookMapper`、`BookMapper.xml`、`MybatisPlusConfig`、`BookMpServiceImpl`。
4. 在 `BookApiApplication.java` 上加上 `@MapperScan("cn.self.studyspringc.**.mapper")`（或者每个 Mapper 接口加 `@Mapper`）。

### 2. 编写测试验证单元测试

```java
@SpringBootTest
class BookMpServiceTest {

    @Autowired
    private IBookMpService bookMpService;

    @Test
    void testInsertAndAutoFill() {
        BookEntity book = BookEntity.builder()
                .title("深入理解 Java 虚拟机")
                .author("周志明")
                .price(new BigDecimal("129.00"))
                .category("TECH")
                .stock(100)
                .build();

        boolean success = bookMpService.save(book);
        Assertions.assertTrue(success);
        Assertions.assertNotNull(book.getId());
        Assertions.assertNotNull(book.getCreateTime()); // 自动填充验证
        System.out.println("自动生成的主键 ID: " + book.getId());
    }

    @Test
    void testPagination() {
        IPage<BookEntity> page = bookMpService.queryPageByConditions(1, 10, "Java", null, null);
        System.out.println("总记录数: " + page.getTotal());
        System.out.println("总页数: " + page.getPages());
        page.getRecords().forEach(System.out::println);
    }
}
```

---

👉 **下一篇推荐学习**：[02. 企业级安全与权限认证：Spring Security 6+ & JWT 拦截鉴权全流程](./02-security-jwt-authentication.md)
