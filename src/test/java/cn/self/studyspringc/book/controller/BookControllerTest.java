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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookController.class)
class BookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookService bookService;

    @MockitoBean
    private BookViewService bookViewService;

    @Test
    void getMissingBookReturns404() throws Exception {
        when(bookService.get(99L)).thenThrow(new BookNotFoundException(99L));

        mockMvc.perform(get("/api/books/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("图书不存在"));
    }

    @Test
    void createBookReturns201AndLocation() throws Exception {
        BookResponse response = new BookResponse(1L, "Spring in Action", "Craig Walls");
        when(bookService.create(any(BookRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Spring in Action\",\"author\":\"Craig Walls\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/books/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Spring in Action"))
                .andExpect(jsonPath("$.author").value("Craig Walls"));
    }

    @Test
    void createBookWithInvalidInputReturns400() throws Exception {
        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"author\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求不合法"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.author").exists());
    }

    @Test
    void getExistingBookReturns200() throws Exception {
        BookResponse response = new BookResponse(1L, "Spring in Action", "Craig Walls");
        when(bookService.get(1L)).thenReturn(response);

        mockMvc.perform(get("/api/books/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Spring in Action"));
    }

    @Test
    void listBooksReturns200() throws Exception {
        List<BookResponse> books = List.of(
                new BookResponse(1L, "Book A", "Author A"),
                new BookResponse(2L, "Book B", "Author B")
        );
        when(bookService.list()).thenReturn(books);

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updateBookReturns200() throws Exception {
        BookResponse response = new BookResponse(1L, "Spring in Action 7th", "Craig Walls");
        when(bookService.update(eq(1L), any(BookRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/books/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Spring in Action 7th\",\"author\":\"Craig Walls\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Spring in Action 7th"));
    }

    @Test
    void deleteBookReturns204() throws Exception {
        doNothing().when(bookService).delete(1L);

        mockMvc.perform(delete("/api/books/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void incrementViewsReturns200() throws Exception {
        when(bookViewService.increment(1L)).thenReturn(5L);

        mockMvc.perform(post("/api/books/1/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(5));
    }

    @Test
    void getViewsReturns200() throws Exception {
        when(bookViewService.get(1L)).thenReturn(5L);

        mockMvc.perform(get("/api/books/1/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(5));
    }
}
