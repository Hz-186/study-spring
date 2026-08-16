package cn.self.studyspringc.book.service;

import cn.self.studyspringc.book.repository.BookRepository;
import cn.self.studyspringc.common.exception.BookNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookViewService {

    private static final String KEY_PREFIX = "book:views:";
    private final BookRepository bookRepository;
    private final StringRedisTemplate redisTemplate;

    public long increment(Long bookId) {
        ensureBookExists(bookId);
        Long views = redisTemplate.opsForValue().increment(key(bookId));
        if (views == null) {
            throw new IllegalStateException("Redis 未返回浏览次数");
        }
        return views;
    }

    public long get(Long bookId) {
        ensureBookExists(bookId);
        String views = redisTemplate.opsForValue().get(key(bookId));
        return views == null ? 0L : Long.parseLong(views);
    }

    private void ensureBookExists(Long bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new BookNotFoundException(bookId);
        }
    }

    private String key(Long bookId) {
        return KEY_PREFIX + bookId;
    }
}
