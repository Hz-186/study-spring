package cn.self.studyspringc.integration.post;

import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/remote-posts")
public class RemotePostController {

    private final PostClient postClient;

    public RemotePostController(PostClient postClient) {
        this.postClient = postClient;
    }

    @GetMapping("/{id}")
    public RemotePost get(@Positive @PathVariable Long id) {
        return postClient.get(id);
    }
}
