package cn.self.studyspringc.book.service;

import cn.self.studyspringc.book.dto.BookRequest;
import cn.self.studyspringc.book.dto.BookResponse;
import cn.self.studyspringc.book.entity.Book;
import cn.self.studyspringc.book.repository.BookRepository;
import cn.self.studyspringc.common.exception.BookNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * =====================================================================================
 * 【业务逻辑层 - BookService 详解】
 * =====================================================================================
 * 
 * 核心技术点：
 * 1. @Transactional(readOnly = true): 类级别声明只读事务优化，提高查询性能。
 * 2. 在写操作方法上（create / update / delete）覆盖标注 @Transactional 开启读写事务。
 * 3. 广泛运用 Java 8 Stream API、Optional API 与 Lambda 函数式编程。
 * =====================================================================================
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookService {
    private final BookRepository bookRepository;

    @Transactional
    public BookResponse create(BookRequest bookRequest) {
        Book book = new Book(bookRequest.getTitle(), bookRequest.getAuthor());
        Book savedBook = bookRepository.save(book);
        return BookResponse.from(savedBook);
    }

    public BookResponse get(Long id) {
        return BookResponse.from(findBook(id));
    }

    /**
     * 【获取全量列表 - Stream 与 方法引用】
     * 
     * 说明：
     * .map(BookResponse::from) 是 Java 8 的【方法引用（Method Reference）】，
     * 它是 Lambda 表达式的极简缩写，等价于：
     * .map(book -> BookResponse.from(book))
     */
    public List<BookResponse> list() {
        return bookRepository.findAll()
                .stream()
                .map(BookResponse::from)
                .toList();
    }

    @Transactional
    public BookResponse update(Long id, BookRequest request) {
        Book book = findBook(id);
        book.update(request.getTitle(), request.getAuthor());
        return BookResponse.from(book);
    }

    @Transactional
    public void delete(Long id) {
        Book book = findBook(id);
        bookRepository.delete(book);
    }

    /**
     * =================================================================================
     * 【重点语法深度拆解：Optional + Lambda 表达式】
     * =================================================================================
     * 
     * 代码：return bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
     * 
     * 1. 为什么返回的是 Optional<Book>？
     *    - bookRepository.findById(id) 返回 Optional<Book> 容器，表示结果可能存在，也可能是 empty。
     *    - 彻底消灭显式的 if (book == null) 判断和潜在的 NullPointerException。
     * 
     * 2. 括号里的 () -> new BookNotFoundException(id) 是什么？
     *    - 它是一个【Lambda 表达式】（匿名函数）。
     *    - 语法拆解：
     *        * ()                        : 代表无入参（对应 Supplier 接口中的 get() 方法无参数）。
     *        * ->                        : Lambda 操作符（箭头），把左边参数导向右边执行体。
     *        * new BookNotFoundException(id) : 方法执行体与返回值，实例化并返回自定义异常对象。
     * 
     * 3. 为什么 orElseThrow 接收的是 Lambda (Supplier)，而不是直接传异常对象？
     *    - 核心机制：【延迟求值 / 惰性执行（Lazy Evaluation）】。
     *    - 性能原因：在 Java 中，创建异常对象（new Exception）必须收集当前线程的整个调用栈轨迹（Stack Trace），
     *               这是一个非常耗费 CPU 和内存的操作。
     *    - 如果直接传对象：orElseThrow(new BookNotFoundException(id)) 会导致无论查没查到数据，都会立即 new 异常，性能暴跌！
     *    - 传 Lambda 表达式：相当于只传递了一份“生产异常的配方”。
     *        * 情况 A（找到了书）：Optional 直接返回 Book，Lambda 表达式【压根不会被执行】，性能开销为 0！
     *        * 情况 B（没找到书）：Optional 为空，此时才触发执行 Lambda，创建并抛出异常。
     * 
     * 4. 历史演进写法对比（它们完全等价）：
     *    ---------------------------------------------------------------------------------
     *    // [写法 1: 远古传统写法]
     *    Book book = bookRepository.findById(id).orElse(null);
     *    if (book == null) {
     *        throw new BookNotFoundException(id);
     *    }
     *    return book;
     * 
     *    // [写法 2: Java 8 早期 - 匿名内部类写法]
     *    return bookRepository.findById(id).orElseThrow(new Supplier<BookNotFoundException>() {
     *        @Override
     *        public BookNotFoundException get() {
     *            return new BookNotFoundException(id);
     *        }
     *    });
     * 
     *    // [写法 3: Java 8 现代标准写法 - Lambda 表达式] (即当前代码)
     *    return bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
     * =================================================================================
     */
    private Book findBook(Long id) {
        return bookRepository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
    }
}
