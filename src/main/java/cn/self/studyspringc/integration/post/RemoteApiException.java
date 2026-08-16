package cn.self.studyspringc.integration.post;

public class RemoteApiException extends RuntimeException {
    public RemoteApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
