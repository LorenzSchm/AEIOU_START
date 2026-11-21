package ai.aeiou.forwarder.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns every failure into {@code {error, message}} so the generated client sees exactly one error shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return body(e.status(), e.error(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    /** Domain services signal a missing row with NoSuchElementException. */
    @ExceptionHandler(java.util.NoSuchElementException.class)
    ResponseEntity<ApiError> notFound(java.util.NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    /** The SSE client went away; there is nobody left to send an error body to. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone() {
        // nothing to do
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        // Spring MVC's own exceptions (no handler, 405, 415, missing params, validation, ...) keep their status.
        if (e instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            return body(status, known == null ? "error" : known.name().toLowerCase(), e.getMessage());
        }
        log.error("Unhandled API error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    private static ResponseEntity<ApiError> body(HttpStatusCode status, String error, String message) {
        return ResponseEntity.status(status).body(new ApiError(error, message));
    }
}
