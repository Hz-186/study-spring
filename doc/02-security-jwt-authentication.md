# 02. 企业级安全与权限认证：Spring Security 6+ & JWT 拦截鉴权全流程

> **模块定位**：系统安全与访问控制层  
> **核心技术栈**：Spring Security 6.x / JJWT 0.12+ / Java 21 / Spring Boot 3/4  
> **学习目标**：彻底掌握前后端分离架构下的无状态身份认证（Authentication）与权限控制（Authorization）。手把手实现 JWT 签发与解析、自定义安全过滤器链、BCrypt 密码哈希加密、401/403 异常标准化响应以及方法级权限拦截。

---

## 一、 为什么企业级系统必须补充 Security + JWT？

当前 `study-spring-c` 工程的所有 HTTP 接口都是公开且无状态的，任何客户端都可以直接发起 `DELETE /api/books/1`。在真实企业级生产环境中，系统必须具备：
1. **身份识别（Who are you?）**：用户必须先通过用户名密码登录，服务器校验通过后签发一段加密的 JSON Web Token (JWT)。
2. **状态透明（Stateless）**：微服务与集群部署场景下，不使用传统的 Tomcat Session（避免 Session 共享与内存开销），客户端后续每次请求在 Header 携带 `Authorization: Bearer <token>`。
3. **细粒度权限管控（What can you do?）**：普通用户（ROLE_USER）只能查看书籍，管理员（ROLE_ADMIN）才能创建、修改和删除。

---

## 二、 依赖配置（`build.gradle`）

在 `build.gradle` 中引入 Spring Security 和目前 Java 社区最标准的 JJWT 库：

```groovy
dependencies {
    // Spring Boot 官方安全 Starter
    implementation 'org.springframework.boot:spring-boot-starter-security'
    
    // JJWT 核心三件套 (最新 0.12.x 规范写法)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
    
    // 测试模块支持
    testImplementation 'org.springframework.security:spring-security-test'
}
```

在 `application.yaml` 中增加 JWT 安全配置参数：

```yaml
app:
  security:
    jwt:
      # 签名密钥：至少 256 位（32 个字符以上），生产环境应通过环境变量注入
      secret-key: "StudySpringSecretKeyForJwtAuthenticationMustBeLongEnough123456"
      # Token 有效期：7 天 (单位：毫秒)
      expiration-ms: 604800000
```

---

## 三、 JWT 工具类设计与语法深度剖析

```java
package cn.self.studyspringc.common.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * =====================================================================================
 * 【JWT 工具类核心机制与语法拆解】
 * =====================================================================================
 * 
 * 1. JWT 结构组成：Header.Payload.Signature (三段 Base64URL 字符串由点 . 拼接)
 *    - Header: 算法与类型 {"alg": "HS256", "typ": "JWT"}
 *    - Payload (Claims): 负载，存放自定义业务字段（如 userId, username, roles）及签发/过期时间。
 *    - Signature: 签名，使用服务器密钥对 (Header+Payload) 进行 HMAC-SHA256 加密，确保防篡改。
 * 
 * 2. JJWT 0.12+ 现代链式 API 语法：
 *    - Jwts.builder(): 开启构建器
 *    - .subject(username): 设置标准主题（通常存用户名或用户ID）
 *    - .claims(claimsMap): 注入自定义负载
 *    - .signWith(key, Jwts.SIG.HS256): 强类型签名
 *    - Jwts.parser().verifyWith(key).build().parseSignedClaims(token): 验签并解析负载
 * =====================================================================================
 */
@Slf4j
@Component
public class JwtUtils {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtUtils(
            @Value("${app.security.jwt.secret-key}") String secret,
            @Value("${app.security.jwt.expiration-ms}") long expirationMs
    ) {
        // 使用 HMAC-SHA 算法生成密钥对象
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * 生成 JWT Token
     * 
     * @param userId 用户 ID
     * @param username 用户名
     * @param roles 角色列表（如 ["ROLE_ADMIN", "ROLE_USER"]）
     * @return 完整的 Bearer Token 字符串
     */
    public String generateToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验 Token，提取所有 Claims 载荷
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("JWT Token 已过期: {}", e.getMessage());
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT Token 格式非法或签名校验失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 提取用户名
     */
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

## 四、 认证过滤器（`JwtAuthenticationFilter`）

```java
package cn.self.studyspringc.common.security.filter;

import cn.self.studyspringc.common.security.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * =====================================================================================
 * 【OncePerRequestFilter 认证过滤器核心逻辑】
 * =====================================================================================
 * 
 * 1. 为什么继承 OncePerRequestFilter？
 *    - 确保在一次 HTTP 请求的处理生命周期中，该过滤器【绝对只被执行一次】（避免跨 Servlet 转发导致重复过滤）。
 * 
 * 2. 拦截与鉴权流程：
 *    Step 1: 从请求头 Authorization 中提取 "Bearer <token>"。
 *    Step 2: 若 Token 存在且合法，解析出 username、userId、roles。
 *    Step 3: 将角色转换为 Spring Security 要求的 SimpleGrantedAuthority 集合。
 *    Step 4: 构造 UsernamePasswordAuthenticationToken 并塞入 SecurityContextHolder。
 *    Step 5: 放行进入后续的 Filter 和 Controller。
 * =====================================================================================
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 获取 Authorization 请求头
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 2. 检查 Header 是否以 Bearer 开头
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            try {
                if (jwtUtils.validateToken(token)) {
                    Claims claims = jwtUtils.parseToken(token);
                    String username = claims.getSubject();
                    
                    @SuppressWarnings("unchecked")
                    List<String> roles = (List<String>) claims.get("roles", List.class);

                    // 转换为 Spring Security 权限对象列表
                    List<SimpleGrantedAuthority> authorities = roles == null ? List.of() :
                            roles.stream()
                                    .map(SimpleGrantedAuthority::new)
                                    .toList();

                    // 构造已认证的 Authentication 令牌
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // 核心关键：将认证信息存入当前线程的 Security 上下文中
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("用户 [{}] 鉴权通过, 拥有的权限: {}", username, authorities);
                }
            } catch (Exception ex) {
                log.warn("SecurityContext 注入认证凭证失败: {}", ex.getMessage());
                // 清理上下文
                SecurityContextHolder.clearContext();
            }
        }

        // 3. 继续执行过滤器链中的下一个过滤器
        filterChain.doFilter(request, response);
    }
}
```

---

## 五、 401 与 403 统一异常处理器

在前后端分离中，当用户未登录或权限不足时，不能由 Spring Security 重定向到默认 HTML 登录页，而必须返回标准 RFC 7807 JSON。

```java
package cn.self.studyspringc.common.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

@Component
public class SecurityJsonExceptionHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 处理 401 Unauthorized：未登录或 Token 无效/过期
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "访问此资源需要有效身份凭证: " + authException.getMessage()
        );
        problem.setTitle("未经授权 (Unauthorized)");
        problem.setType(URI.create("about:blank"));

        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    /**
     * 处理 403 Forbidden：已登录但角色权限不足
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "您没有权限执行此操作"
        );
        problem.setTitle("禁止访问 (Forbidden)");
        problem.setType(URI.create("about:blank"));

        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
```

---

## 六、 Spring Security 6 核心配置类（Lambda DSL 语法）

```java
package cn.self.studyspringc.common.security.config;

import cn.self.studyspringc.common.security.filter.JwtAuthenticationFilter;
import cn.self.studyspringc.common.security.handler.SecurityJsonExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * =====================================================================================
 * 【Spring Security 6 核心配置深度拆解】
 * =====================================================================================
 * 
 * 1. @EnableMethodSecurity(prePostEnabled = true):
 *    - 开启方法级别权限控制（允许在 Controller 方法上使用 @PreAuthorize 注解）。
 * 
 * 2. 弃用说明：
 *    - Spring Security 5.7 / 6+ 已经彻底废弃了 WebSecurityConfigurerAdapter。
 *    - 现在必须通过注入 `SecurityFilterChain` Bean 的方式，采用全 Lambda 链式 DSL 进行配置。
 * 
 * 3. 核心配置规则：
 *    - csrf(AbstractHttpConfigurer::disable): 禁用 CSRF（因为前后端分离使用 Token，无需 Cookie 防御 CSRF）。
 *    - sessionManagement(...STATELESS): 告诉 Spring 不要创建 HttpSession，完全无状态。
 *    - authorizeHttpRequests(...): 配置 URL 白名单和受保护规则。
 *    - addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class): 将自定义 JWT 过滤器插在内置账号密码过滤器之前。
 * =====================================================================================
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityJsonExceptionHandler securityExceptionHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. 禁用 CSRF 防护与表单默认登录
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // 2. 设置 Session 为无状态
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. 配置统一异常处理器
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityExceptionHandler)
                        .accessDeniedHandler(securityExceptionHandler)
                )

                // 4. 配置请求权限白名单与拦截规则
                .authorizeHttpRequests(auth -> auth
                        // 登录、注册放行
                        .requestMatchers("/api/auth/**").permitAll()
                        // Swagger 接口文档放行
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 开放 Actuator 健康检查
                        .requestMatchers("/actuator/health").permitAll()
                        // 查询图书允许所有人访问
                        .requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()
                        // 增删改图书必须具备 ADMIN 角色
                        .requestMatchers(HttpMethod.POST, "/api/books/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/books/**").hasRole("ADMIN")
                        // 其余所有请求都需要认证通过
                        .anyRequest().authenticated()
                )

                // 5. 挂载 JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 强哈希密码加密器 (BCrypt)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

---

## 七、 认证 Controller 与接口级权限实战

### 1. 登录与注册 DTO

```java
package cn.self.studyspringc.common.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {}

public record AuthResponse(
        String token,
        String tokenType,
        String username,
        java.util.List<String> roles
) {
    public static AuthResponse of(String token, String username, java.util.List<String> roles) {
        return new AuthResponse(token, "Bearer", username, roles);
    }
}
```

### 2. 认证控制器（`AuthController`）

```java
package cn.self.studyspringc.common.security.controller;

import cn.self.studyspringc.common.security.dto.AuthResponse;
import cn.self.studyspringc.common.security.dto.LoginRequest;
import cn.self.studyspringc.common.security.jwt.JwtUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    /**
     * 模拟登录接口
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // 演示代码：模拟数据库查询用户（密码为 123456 的 BCrypt 密文）
        String mockEncodedPassword = passwordEncoder.encode("123456");

        if ("admin".equals(request.username()) && passwordEncoder.matches(request.password(), mockEncodedPassword)) {
            // 管理员用户：拥有 ADMIN 和 USER 权限
            List<String> roles = List.of("ROLE_ADMIN", "ROLE_USER");
            String token = jwtUtils.generateToken(1L, "admin", roles);
            return ResponseEntity.ok(AuthResponse.of(token, "admin", roles));
        } else if ("user".equals(request.username()) && passwordEncoder.matches(request.password(), mockEncodedPassword)) {
            // 普通用户：仅有 USER 权限
            List<String> roles = List.of("ROLE_USER");
            String token = jwtUtils.generateToken(2L, "user", roles);
            return ResponseEntity.ok(AuthResponse.of(token, "user", roles));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("用户名或密码错误");
    }

    /**
     * 演示方法级细粒度权限注解 @PreAuthorize
     */
    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnlyEndpoint() {
        return "恭喜你！只有具备 ROLE_ADMIN 角色的管理员才能看到本内容。";
    }
}
```

---

## 八、 实战演练与落地自测

### 1. 验证步骤
1. 使用 cURL 发起登录获取管理员 Token：
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin", "password":"123456"}'
```
2. 响应返回：`{"token":"eyJhbGciOi...", "tokenType":"Bearer", ...}`。
3. 携带 Token 请求受保护接口（创建书籍）：
```bash
curl -X POST http://localhost:8080/api/books \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -d '{"title":"Spring Cloud 实战", "author":"张三"}'
```
4. 不带 Token 或使用普通用户 Token 删除书籍，验证是否精准触发 **401 Unauthorized** 或 **403 Forbidden**。

---

👉 **下一篇推荐学习**：[03. Spring 核心切面与拦截器：AOP 日志/防刷 + Interceptor + Filter 链路追踪](./03-aop-interceptor-filters.md)
