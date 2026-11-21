package ai.aeiou.forwarder.api;

import static ai.aeiou.forwarder.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<JsonNode> problem(Problem p) {
    return ResponseEntity.status(p.status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            obj(
                "type",
                "about:blank",
                "title",
                HttpStatus.valueOf(p.status).getReasonPhrase(),
                "status",
                p.status,
                "code",
                p.code,
                "detail",
                p.getMessage(),
                "requestId",
                UUID.randomUUID().toString()));
  }

  @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class})
  ResponseEntity<JsonNode> invalid(Exception ignored) {
    return problem(Problem.invalid("Invalid or missing request field"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<JsonNode> unavailable(Exception ignored) {
    return problem(new Problem(503, "STORE_UNAVAILABLE", "Database operation unavailable"));
  }
}
