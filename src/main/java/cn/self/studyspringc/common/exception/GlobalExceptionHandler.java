package cn.self.studyspringc.common.exception;

import cn.self.studyspringc.integration.post.RemoteApiException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器 (Global Exception Handler)
 *
 * 【核心注解说明】
 * @RestControllerAdvice:
 * 1. 组合注解：由 @ControllerAdvice 和 @ResponseBody 组合而成。
 * 2. 全局拦截：基于 Spring AOP 机制，自动拦截所有 @RestController 控制器中抛出的异常。
 * 3. 响应格式：方法返回的对象会自动被 Jackson 序列化为 JSON 格式并写入 HTTP 响应体中。
 *
 * 【返回数据结构】
 * 采用 RFC 7807 国际标准规范定义的 ProblemDetail 对象，提供标准化、结构化的错误信息。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：图书未找到 (404 Not Found)
     *
     * @param exception 捕获到的 BookNotFoundException 实例
     * @return 包含 404 状态码和提示信息的 ProblemDetail 对象
     *
     * 【语法要点】
     * @ExceptionHandler(BookNotFoundException.class): 声明该方法专门捕获并处理 BookNotFoundException 类型的异常。
     * ProblemDetail.forStatusAndDetail(...): 工厂方法，快速构建带有 HTTP 状态码和详细描述的 ProblemDetail 实例。
     */
    @ExceptionHandler(BookNotFoundException.class)
    public ProblemDetail handleNotFound(BookNotFoundException exception) {
        // 创建 404 NOT_FOUND 状态的响应体，并设置 detail 为异常中的 message
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        // 设置错误的简要标题
        problem.setTitle("图书不存在");
        return problem;
    }

    /**
     * 处理请求体参数校验失败异常 (400 Bad Request)
     * 场景：@RequestBody 绑定的 DTO 实体类通过 @Valid / @Validated 校验失败时触发
     *
     * @param exception 捕获到的 MethodArgumentNotValidException 校验异常
     * @return 包含 400 状态码及详细字段错误清单（errors）的 ProblemDetail 对象
     *
     * 【语法与流式编程要点】
     * 1. exception.getBindingResult().getFieldErrors(): 获取所有校验未通过的字段错误列表 (List<FieldError>)。
     * 2. .stream(): 开启 Java 8 Stream 流式处理，将列表元素放入流水线中支持链式加工。
     * 3. Collectors.toMap(KeyMapper, ValueMapper, MergeFunction): 将流中的元素收集并转换为 Map<String, String> 键值对：
     *    - Key 映射: `error -> error.getField()` (Lambda 表达式，提取校验失败的属性字段名，如 "title")
     *    - Value 映射: `DefaultMessageSourceResolvable::getDefaultMessage` (方法引用，提取该字段的错误提示信息，如 "书名不能为空")
     *    - 冲突合并策略: `(first, ignored) -> first` (Lambda 表达式，当同一个字段存在多个校验错误时，保留第 1 条，忽略后续的)
     * 4. problem.setProperty("errors", errors): 在标准 ProblemDetail 响应体中扩展自定义属性字段 "errors"。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        // 1. 提取所有校验不通过的字段及其错误信息，组装成 Map (Key: 字段名, Value: 错误提示)
        Map<String, String> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        DefaultMessageSourceResolvable::getDefaultMessage,
                        (first, ignored) -> first
                ));

        // 2. 构建 400 BAD_REQUEST 基础错误对象
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        problem.setTitle("请求不合法");

        // 3. 将自定义的错误清单 Map 放入扩展属性中返回给前端
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * 处理单参数/路径参数约束违反异常 (400 Bad Request)
     * 场景：Controller 类上标注 @Validated，并在 @RequestParam 或 @PathVariable 上使用的校验注解（如 @Min, @NotBlank）不满足时触发
     *
     * @param exception 捕获到的 ConstraintViolationException 异常
     * @return 包含 400 状态码和违规信息的 ProblemDetail
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /**
     * 处理 Spring 6 / Spring Boot 3 方法级参数校验异常 (400 Bad Request)
     * 场景：Spring 6 新增的方法参数声明式校验机制触发的异常类型
     *
     * @param exception 捕获到的 HandlerMethodValidationException 异常
     * @return 包含 400 状态码的 ProblemDetail
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /**
     * 处理参数类型转换不匹配异常 (400 Bad Request)
     * 场景：请求参数类型无法转换，例如接口定义 Long id，客户端却传递了字符串 "abc"
     *
     * @param exception 捕获到的 MethodArgumentTypeMismatchException 异常
     * @return 包含 400 状态码和类型错误详情的 ProblemDetail
     *
     * 【语法要点】
     * exception.getName(): 获取导致类型转换失败的参数名称（如 "id"）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "参数类型不匹配: " + exception.getName()
        );
        problem.setTitle("参数类型错误");
        return problem;
    }

    /**
     * 处理远程/外部 API 调用异常 (502 Bad Gateway)
     * 场景：调用第三方外部接口（如 HTTP 外部服务、远程微服务等）失败或不可用时抛出
     *
     * @param exception 捕获到的 RemoteApiException 异常
     * @return 包含 502 状态码和下游错误信息的 ProblemDetail
     *
     * 【语法要点】
     * HttpStatus.BAD_GATEWAY: HTTP 状态码 502，表示作为网关或代理的服务器从上游收到无效响应。
     */
    @ExceptionHandler(RemoteApiException.class)
    public ProblemDetail handleRemoteApi(RemoteApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setTitle("远程服务调用失败");
        return problem;
    }
}
