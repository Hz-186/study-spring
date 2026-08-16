package cn.self.studyspringc.book.controller;

import cn.self.studyspringc.book.dto.BookRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.service.BookService;
import cn.self.studyspringc.book.service.BookViewService;
import cn.self.studyspringc.common.exception.BookNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

// =====================================================================================
// 【Java 静态导入 (Static Import) 机制说明】
// =====================================================================================
// 语法：import static 包名.类名.静态方法名; 或 import static 包名.类名.*;
// 作用：允许在当前类中直接调用某个类的静态方法，而无需写出类名作为前缀。
// 优点：编写测试断言和 Mockito 打桩时，代码读起来如同自然语言，流畅简洁（领域特定语言 DSL 风格）。
//
// 1. Mockito 参数匹配器与打桩工具：
//    - any(Class): 只要是该类型的任意参数即可匹配
//    - eq(value): 精确匹配某个入参值
//    - doNothing(): 对 void 无返回值方法假装正常执行
//    - when(): 设定替身被调用时的行为（打桩入口）
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

// 2. Spring MockMvc 请求构造器（用于模拟构造各种 HTTP 请求）：
//    - get(url) / post(url) / put(url) / delete(url)
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

// 3. Spring MockMvc 结果匹配器（用于断言和验证 HTTP 响应）：
//    - status(): 断言 HTTP 状态码（isOk, isCreated, isNotFound, isBadRequest 等）
//    - header(): 断言响应头（如 Location 路径）
//    - jsonPath(): 断言响应 JSON 体内的字段和值
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =====================================================================================
 * 【Spring MVC 控制器单元测试 / 切片测试 (Slice Test) 核心指南】
 * =====================================================================================
 * 
 * 1. 什么是 @WebMvcTest？（切片测试核心）
 *    - 作用：专注于测试 Spring MVC 控制器（Web 层），属于【切片测试（Slice Test）】。
 *    - 与 @SpringBootTest 的本质区别：
 *      * @SpringBootTest：会引导启动【完整 Spring 容器】，包括数据源 (DataSource)、JPA/MyBatis、
 *        Redis、所有 Service/Repository/Component。启动耗时长（数秒至十几秒），适合端到端集成测试。
 *      * @WebMvcTest(BookController.class)：【仅实例化 Web 层相关组件】（指定的 Controller、
 *        全局异常处理器 @RestControllerAdvice、参数校验器 Validator、Jackson JSON 序列化器、过滤器等）。
 *        不会加载任何 Service 和数据库组件，启动时间在毫秒级别，极其轻量迅速。
 * 
 * 2. 为什么单元测试中需要 Mock（替身）？
 *    - 控制器单元测试的核心目标：只验证 Controller 本身是否正确处理了 URL 路由、HTTP 方法、参数校验、
 *      响应状态码和 JSON 转换，而不应该受底层 Service 业务逻辑或数据库环境（如网络断开、表缺失）的影响。
 *    - 因此，通过 Mock 框架（Mockito）为底层依赖制作“虚拟替身”，并提前编排好它的行为（打桩 Stubbing）。
 * 
 * 3. 核心注解速查：
 *    - @Test:
 *      * 来源：JUnit 5 (org.junit.jupiter.api.Test)。
 *      * 作用：标记该方法为一个独立的自动化测试用例。测试运行器会自动发现并执行它。
 *      * 规范：方法必须是 void，无参数，JUnit 5 推荐默认包级私有（无需写 public）。
 *    - @Autowired:
 *      * 作用：Spring 依赖注入注解。这里用于自动注入 Spring 测试框架初始化的 MockMvc 工具。
 *    - @MockitoBean:
 *      * 来源：Spring Boot 3.4+ 最新引入的注解（替换旧版本的 @MockBean）。
 *      * 作用：在 Spring 测试上下文中创建一个 Mockito 模拟对象（替身），并自动注入给 Controller 的依赖。
 * 
 * 4. MockMvc 标准测试三部曲（AAA 模式 / Given-When-Then）：
 *    ① Given（设定剧本/打桩）：when(service.method(...)).thenReturn(...);
 *    ② When（发起模拟请求）：mockMvc.perform(get("/api/books/1"));
 *    ③ Then（断言响应结果）：.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
 * 
 * 5. JSONPath 表达式基础：
 *    - $             : 代表 JSON 根对象
 *    - $.id          : 根对象中的 id 属性
 *    - $.title       : 根对象中的 title 属性
 *    - $.errors.title: 根对象下的 errors 对象中的 title 属性
 *    - $.length()    : 数组的元素个数
 * =====================================================================================
 */
@WebMvcTest(BookController.class)
class BookControllerTest {

    /**
     * MockMvc：Spring MVC 测试的核心驱动工具（类似于内存中的虚拟 Postman / 虚拟浏览器）。
     * 能够在不启动真实网络端口（如 8080）的情况下，在内存中直接模拟发送 HTTP 请求并捕获完整响应。
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * @MockitoBean：创建 BookService 的 Mock 替身对象。
     * Controller 中通过构造器注入的 bookService 会自动被替换成这个假对象。
     */
    @MockitoBean
    private BookService bookService;

    /**
     * @MockitoBean：创建 BookViewService 的 Mock 替身对象。
     */
    @MockitoBean
    private BookViewService bookViewService;

    /**
     * 【测试场景 1：查询不存在的书籍，预期返回 404 Not Found】
     * 
     * 测试目标：
     * 1. 验证当底层抛出自定义业务异常 BookNotFoundException 时，全局异常拦截器（@RestControllerAdvice）
     *    能否正确捕获并将 HTTP 状态码转换为 404。
     * 2. 验证返回的错误 JSON 格式是否符合 RFC 7807 / 统一错误结构。
     */
    @Test
    void getMissingBookReturns404() throws Exception {
        // ① Given：设定剧本 —— 当有人调用 bookService.get(99L) 时，假装抛出书籍未找到异常
        when(bookService.get(99L)).thenThrow(new BookNotFoundException(99L));

        // ② When & ③ Then：模拟发起 GET 请求并断言结果
        mockMvc.perform(get("/api/books/99"))
                // 断言：HTTP 状态码必须是 404 (Not Found)
                .andExpect(status().isNotFound())
                // 断言：返回的错误 JSON 中 title 字段必须为 "图书不存在"
                .andExpect(jsonPath("$.title").value("图书不存在"));
    }

    /**
     * 【测试场景 2：成功新增书籍，预期返回 201 Created 与 Location 响应头】
     * 
     * 测试目标：
     * 1. 验证 POST 请求正常接收 JSON 并反序列化为 BookRequest 对象。
     * 2. 验证 Controller 正确返回符合 RESTful 规范的 HTTP 201 Created 状态码。
     * 3. 验证响应头 Header 中是否正确携带 "Location: /api/books/1" 引导客户端访问新建资源。
     * 4. 验证响应体 Body 中正确序列化了新创建的书籍信息。
     */
    @Test
    void createBookReturns201AndLocation() throws Exception {
        // ① Given：准备模拟返回值，并设定：只要接收到任意 BookRequest 参数，就返回预设的 BookResponse
        BookResponse response = new BookResponse(1L, "Spring in Action", "Craig Walls");
        when(bookService.create(any(BookRequest.class))).thenReturn(response);

        // ② When：模拟发起 POST 请求，指定 Content-Type 为 JSON，并在请求体中写入 JSON 字符串
        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Spring in Action\",\"author\":\"Craig Walls\"}"))
                // ③ Then：断言状态码必须为 201
                .andExpect(status().isCreated())
                // 断言响应头必须包含新资源的 URI 路径
                .andExpect(header().string("Location", "/api/books/1"))
                // 断言响应 JSON 体的关键字段
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Spring in Action"))
                .andExpect(jsonPath("$.author").value("Craig Walls"));
    }

    /**
     * 【测试场景 3：提交非法数据新增书籍，预期返回 400 Bad Request（参数校验测试）】
     * 
     * 测试目标：
     * 1. 验证 Controller 上的 @Valid 注解是否生效。
     * 2. 当 title 传空格、author 传空字符串时（违反 DTO 上的 @NotBlank 规则），Spring 是否自动拦截。
     * 3. 验证全局异常拦截器是否返回 400 状态码，且错误详情中是否包含 title 和 author 的错误说明。
     * 
     * 注意：此测试不需要 when(...) 打桩，因为请求在进入 Controller 方法体之前就被 Spring 参数校验拦截了。
     */
    @Test
    void createBookWithInvalidInputReturns400() throws Exception {
        // ② When：发送不合法的请求体（空白书名、空作者）
        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"author\":\"\"}"))
                // ③ Then：断言被校验拦截，返回 400 Bad Request
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求不合法"))
                // 断言 errors 对象中分别存在针对 title 和 author 的校验失败信息
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.author").exists());
    }

    /**
     * 【测试场景 4：根据 ID 成功查询已存在的书籍，预期返回 200 OK】
     * 
     * 测试目标：
     * 1. 验证 GET /api/books/{id} 的路径变量解析（@PathVariable）。
     * 2. 验证 Controller 返回对象被正确转换为 JSON 并返回 200 状态码。
     */
    @Test
    void getExistingBookReturns200() throws Exception {
        // ① Given：设定当查询 ID 为 1 时返回预设书籍
        BookResponse response = new BookResponse(1L, "Spring in Action", "Craig Walls");
        when(bookService.get(1L)).thenReturn(response);

        // ② When & ③ Then：发送 GET 请求并验证 200 状态码与内容
        mockMvc.perform(get("/api/books/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Spring in Action"));
    }

    /**
     * 【测试场景 5：获取全量书籍列表，预期返回 200 OK 与 JSON 数组】
     * 
     * 测试目标：
     * 1. 验证 GET /api/books 集合查询接口。
     * 2. 验证 List<BookResponse> 集合被正确转换为 JSON Array。
     * 3. 验证 JSONPath 的 $.length() 函数能够正确断言数组长度。
     */
    @Test
    void listBooksReturns200() throws Exception {
        // ① Given：准备包含 2 本书的列表并打桩
        List<BookResponse> books = List.of(
                new BookResponse(1L, "Book A", "Author A"),
                new BookResponse(2L, "Book B", "Author B")
        );
        when(bookService.list()).thenReturn(books);

        // ② When & ③ Then：发起 GET 请求并验证返回数组长度为 2
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * 【测试场景 6：更新已存在的书籍，预期返回 200 OK】
     * 
     * 测试目标：
     * 1. 验证 PUT /api/books/{id} 接口同时接收 PathVariable 与 RequestBody。
     * 2. 验证 eq(1L) 精确匹配器与 any(BookRequest.class) 泛匹配器的组合使用。
     */
    @Test
    void updateBookReturns200() throws Exception {
        // ① Given：设定当更新 ID=1L 的书籍时，返回修改后的结果
        BookResponse response = new BookResponse(1L, "Spring in Action 7th", "Craig Walls");
        when(bookService.update(eq(1L), any(BookRequest.class))).thenReturn(response);

        // ② When & ③ Then：发送 PUT 请求并验证返回的数据
        mockMvc.perform(put("/api/books/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Spring in Action 7th\",\"author\":\"Craig Walls\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Spring in Action 7th"));
    }

    /**
     * 【测试场景 7：根据 ID 删除书籍，预期返回 204 No Content】
     * 
     * 测试目标：
     * 1. 验证 DELETE /api/books/{id} 接口。
     * 2. 验证针对无返回值（void）的 Service 方法，使用 doNothing().when(...).delete(1L) 打桩。
     * 3. 验证 Controller 正确返回符合 RESTful 规范的 204 No Content（操作成功且无需返回 Body）。
     */
    @Test
    void deleteBookReturns204() throws Exception {
        // ① Given：对 void 方法打桩，假装删除成功且不抛异常
        doNothing().when(bookService).delete(1L);

        // ② When & ③ Then：发送 DELETE 请求并断言 204
        mockMvc.perform(delete("/api/books/1"))
                .andExpect(status().isNoContent());
    }

    /**
     * 【测试场景 8：递增书籍浏览量，预期返回 200 OK 与包含 views 的 Map】
     * 
     * 测试目标：
     * 1. 验证 POST /api/books/{id}/views 路由。
     * 2. 验证 Controller 返回 Map.of("views", 5L) 时，JSON 正确生成 {"views": 5}。
     */
    @Test
    void incrementViewsReturns200() throws Exception {
        // ① Given：设定 bookViewService.increment(1L) 模拟返回 5
        when(bookViewService.increment(1L)).thenReturn(5L);

        // ② When & ③ Then：发送 POST 请求并断言 JSON 中的 views 属性
        mockMvc.perform(post("/api/books/1/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(5));
    }

    /**
     * 【测试场景 9：获取书籍浏览量，预期返回 200 OK 与浏览量数值】
     * 
     * 测试目标：
     * 1. 验证 GET /api/books/{id}/views 路由。
     * 2. 验证单独获取浏览量接口的正确性。
     */
    @Test
    void getViewsReturns200() throws Exception {
        // ① Given：设定 bookViewService.get(1L) 模拟返回 5
        when(bookViewService.get(1L)).thenReturn(5L);

        // ② When & ③ Then：发送 GET 请求并断言
        mockMvc.perform(get("/api/books/1/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(5));
    }
}

