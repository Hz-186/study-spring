package cn.self.studyspringc.common.exception;

public class BookNotFoundException extends RuntimeException {
    public BookNotFoundException(Long id) {
        super("book not found with id:" + id);
    }
}
