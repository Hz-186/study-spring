package cn.self.studyspringc.integration.post;

public record RemotePost(
        Long userId,
        Long id,
        String title,
        String body
) {
}
