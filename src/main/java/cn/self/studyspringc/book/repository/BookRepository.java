package cn.self.studyspringc.book.repository;

import cn.self.studyspringc.book.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * =====================================================================================
 * 【Spring Data JPA - Repository 详解】
 * =====================================================================================
 * 
 * 1. 为什么这里只有 interface，一行实现代码都没写？
 *    - 核心机制：Spring 的【动态代理（Dynamic Proxy）】技术。
 *    - 当 Spring 容器启动时，会自动扫描所有继承 Repository 的接口，在内存中动态生成代理实现类
 *      （底层默认核心实现类为 SimpleJpaRepository），并根据方法名自动翻译生成对应的 SQL 语句交给数据库执行。
 * 
 * 2. 泛型参数说明：JpaRepository<Book, Long>
 *    - 第一个泛型【Book】：代表当前 Repository 操作的实体类（Entity）对应的数据表。
 *    - 第二个泛型【Long】：代表该实体类的主键（ID）的数据类型。
 * 
 * 3. 接口继承家族体系（自顶向下）：
 *    - Repository<T, ID>                 : 顶层标记接口，无任何方法。
 *    - CrudRepository<T, ID>             : 提供最基础的增删改查（CRUD）能力。
 *    - PagingAndSortingRepository<T, ID> : 提供分页（Pageable）与排序（Sort）功能。
 *    - JpaRepository<Book, Long>         : 提供 JPA 独有高级功能（批量操作 batch、立即刷新 flush、持久化缓存管理等）。
 * 
 * 4. 继承后自动拥有的内置核心函数清单：
 *    ----------------------------------------------------------------------------------
 *    【查询相关】
 *    - Optional<Book> findById(Long id)         : 根据主键 ID 查找（返回 Optional 容器，防止空指针）。
 *    - List<Book> findAll()                     : 查询表中所有数据。
 *    - List<Book> findAllById(Iterable<Long> ids): 根据一组 ID 批量查询（相当于 SQL: WHERE id IN (...)）。
 *    - boolean existsById(Long id)              : 判断指定 ID 记录是否存在（只做轻量判断，不查整行数据）。
 *    - long count()                             : 统计整张表的总记录数（相当于 SQL: SELECT COUNT(*)）。
 *    ----------------------------------------------------------------------------------
 *    【保存与更新相关】
 *    - Book save(Book entity)                   : 保存单条记录。
 *                                                 • 若 ID 为 null -> 执行 INSERT 新增；
 *                                                 • 若 ID 已存在 -> 执行 UPDATE 更新。
 *    - List<Book> saveAll(Iterable<Book> entities): 批量保存或更新多条记录。
 *    - Book saveAndFlush(Book entity)           : 保存并立即刷新到数据库（不等事务提交，立即执行 SQL）。
 *    ----------------------------------------------------------------------------------
 *    【删除相关】
 *    - void deleteById(Long id)                 : 根据主键 ID 删除单条记录。
 *    - void delete(Book entity)                 : 根据实体对象删除记录。
 *    - void deleteAllById(Iterable<Long> ids)   : 根据一批 ID 批量删除。
 *    - void deleteAll()                         : 清空整张表（逐条查询后再逐条删除）。
 *    - void deleteAllInBatch()                  : 高性能一键清空表（直接执行一条 DELETE FROM book SQL）。
 *    ----------------------------------------------------------------------------------
 *    【分页与排序】
 *    - Page<Book> findAll(Pageable pageable)    : 分页查询（如 PageRequest.of(0, 10) 查第 1 页每页 10 条）。
 *    - List<Book> findAll(Sort sort)            : 排序查询（如 Sort.by("id").descending() 按 ID 降序）。
 * =====================================================================================
 */
public interface BookRepository extends JpaRepository<Book, Long> {

    // =================================================================================
    // 【拓展能力：方法名派生查询（Method Name Derived Query）示范】
    // 规则：只要按照 Spring 的命名规范声明方法，Spring 就会自动将其翻译为对应的 SQL 语句！
    // =================================================================================

    /**
     * 1. 精确查询：根据书名查找
     * 自动生成 SQL: SELECT * FROM books WHERE title = ?
     */
    List<Book> findByTitle(String title);

    /**
     * 2. 多条件查询：根据作者和书名同时查询
     * 自动生成 SQL: SELECT * FROM books WHERE author = ? AND title = ?
     */
    List<Book> findByAuthorAndTitle(String author, String title);

    /**
     * 3. 模糊查询：书名包含指定关键字
     * 自动生成 SQL: SELECT * FROM books WHERE title LIKE '%keyword%'
     */
    List<Book> findByTitleContaining(String keyword);

    /**
     * 4. 存在性检查：检查某个作者是否有书存在
     * 自动生成 SQL: SELECT COUNT(*) > 0 FROM books WHERE author = ?
     */
    boolean existsByAuthor(String author);

    /**
     * 5. 单条唯一查询：根据书名返回 Optional 包装
     * 自动生成 SQL: SELECT * FROM books WHERE title = ? LIMIT 1
     */
    Optional<Book> findFirstByTitle(String title);
}

