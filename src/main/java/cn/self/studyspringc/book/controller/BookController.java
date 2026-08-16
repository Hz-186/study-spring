package cn.self.studyspringc.book.controller;

import cn.self.studyspringc.book.dto.BookRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.service.BookService;
import cn.self.studyspringc.book.service.BookViewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================================
 * 【Spring MVC 控制器与参数校验注解详解】
 * =====================================================================================
 * 
 * 1. @Validated
 *    - 作用：开启 Spring 的【方法级参数校验】支持。
 *    - 当在单个参数前加校验注解（如 @Positive @PathVariable Long id）时，必须在类上标注 @Validated，
 *      校验不通过时会抛出 ConstraintViolationException。
 * 
 * 2. @RestController
 *    - 作用：组合注解（@Controller + @ResponseBody）。
 *    - 标志该类为 REST 风格控制器，类中所有方法的返回值都会自动被 Jackson 序列化为 JSON 格式返回给前端。
 * 
 * 3. @RequestMapping("/api/books")
 *    - 作用：定义该 Controller 下所有接口的基础 URL 映射路径。
 * 
 * 4. @RequiredArgsConstructor (Lombok)
 *    - 作用：为所有 final 修饰的成员变量自动生成包含这些参数的构造函数，实现 Spring 官方推荐的【构造器依赖注入】。
 * =====================================================================================
 */
@Validated
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
@Slf4j
public class BookController {

    private final BookService bookService;
    private final BookViewService bookViewService;

    /**
     * 【新增书籍接口 - 遵循标准 RESTful 规范】
     * 
     * 参数注解说明：
     * 1. @RequestBody: 告诉 Spring 从 HTTP 请求体（Body）中读取 JSON 字符串，
     *                 并反序列化为 Java 对象 BookRequest。
     * 2. @Valid: 开启嵌套对象的校验开关！通知 Spring 触发检查 BookRequest 内部声明的所有字段规则
     *            （如 @NotBlank、@NotNull、@Min 等）。如果不加 @Valid，DTO 内部的校验注解将不会生效。
     * 
     * 返回值与代码逐行说明：
     * - ResponseEntity<BookResponse>: Spring 提供的全功能 HTTP 响应实体（包含状态码、响应头、响应体）。
     * - URI location = URI.create("/api/books/" + response.id()):
     *     * URI (Uniform Resource Identifier): Java 原生类（java.net.URI），代表资源的统一标识路径。
     *     * 这里拼接出新建书籍的专属访问地址（如 "/api/books/5"）。
     * - ResponseEntity.created(location).body(response):
     *     * 返回 HTTP 状态码 201 Created（表示新资源已成功创建，比普通 200 OK 更符合 RESTful 规范）。
     *     * 在响应头（Header）中自动添加 "Location: /api/books/5"，告诉客户端可以在哪里访问新创建的资源。
     *     * 在响应体（Body）中返回创建好的 BookResponse JSON 数据。
     */
    @PostMapping
    public ResponseEntity<BookResponse> create(@Valid @RequestBody BookRequest request) {
        BookResponse response = bookService.create(request);
        URI location = URI.create("/api/books/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 【根据 ID 获取单本书籍】
     * 
     * 参数注解说明：
     * 1. @PathVariable: 路径变量注解。告诉 Spring 从 URL 路径中截取 {id} 的值注入给方法入参。
     * 2. @Positive: Jakarta Validation 校验注解。限制 id 必须是严格大于 0 的正数（id > 0）。
     *               如果前端传入 0 或负数（如 /api/books/-1），Spring 会直接拦截报错，无需手动写 if(id <= 0)。
     */
    @GetMapping("/{id}")
    public BookResponse get(@Positive @PathVariable Long id) {
        return bookService.get(id);
    }

    /**
     * 【获取书籍全量列表】
     */
    @GetMapping
    public List<BookResponse> list() {
        return bookService.list();
    }

    /**
     * 【更新书籍接口】
     * 
     * 组合参数注解：
     * - @Positive @PathVariable Long id    : 从 URL 中提取并校验 ID 必须为正整数。
     * - @Valid @RequestBody BookRequest request : 从请求体提取 JSON 并校验内部各个字段规则。
     */
    @PutMapping("/{id}")
    public BookResponse update(
            @Positive @PathVariable Long id,
            @Valid @RequestBody BookRequest request
    ) {
        return bookService.update(id, request);
    }

    /**
     * 【删除书籍接口】
     * 
     * - ResponseEntity.noContent().build():
     *   返回 HTTP 状态码 204 No Content（表示操作成功执行，且响应体中无需返回任何数据）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Positive @PathVariable Long id) {
        bookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 【增加书籍浏览量】
     */
    @PostMapping("/{id}/views")
    public Map<String, Long> incrementViews(@Positive @PathVariable Long id) {
        return Map.of("views", bookViewService.increment(id));
    }

    /**
     * 【获取书籍浏览量】
     */
    @GetMapping("/{id}/views")
    public Map<String, Long> getViews(@Positive @PathVariable Long id) {
        return Map.of("views", bookViewService.get(id));
    }
}
