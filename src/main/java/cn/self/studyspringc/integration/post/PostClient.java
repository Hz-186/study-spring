package cn.self.studyspringc.integration.post;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PostClient {

    private final RestClient restClient;

    public PostClient(@Qualifier("postRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public RemotePost get(Long id) {
        try {
            RemotePost post = restClient.get()
                    .uri("/posts/{id}", id)
                    .retrieve()
                    .body(RemotePost.class);
            if (post == null) {
                throw new RemoteApiException("远程文章接口返回空响应", null);
            }
            return post;
        } catch (RestClientResponseException exception) {
            throw new RemoteApiException(
                    "远程文章接口返回状态 " + exception.getStatusCode(),
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw new RemoteApiException("无法访问远程文章接口", exception);
        }
    }
}
