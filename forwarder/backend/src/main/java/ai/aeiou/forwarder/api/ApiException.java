package ai.aeiou.forwarder.api;

import org.springframework.http.HttpStatus;

/** An error that maps 1:1 to an HTTP status and an {@link ApiError} body. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " " + id + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException conflict(String error, String message) {
        return new ApiException(HttpStatus.CONFLICT, error, message);
    }

    public static ApiException notImplemented(String message) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "not_implemented", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
