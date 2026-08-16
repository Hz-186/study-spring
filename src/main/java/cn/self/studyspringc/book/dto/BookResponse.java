package cn.self.studyspringc.book.dto;

import cn.self.studyspringc.book.entity.Book;

public record BookResponse(Long id, String title, String author) {
    public static BookResponse from(Book book) {
        return new BookResponse(book.getId(), book.getTitle(), book.getAuthor());
    }
}
