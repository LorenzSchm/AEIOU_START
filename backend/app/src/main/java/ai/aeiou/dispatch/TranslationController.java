package ai.aeiou.dispatch;

import org.springframework.web.bind.annotation.*;

/** Operator-only translation. It changes no dispatch data, so a read-only desk may call it. */
@RestController
@RequestMapping("/api/v1")
class TranslationController {
  static final String PATH = "/api/v1/translations";
  private final Translator translator;

  TranslationController(Translator translator) {
    this.translator = translator;
  }

  @PostMapping("/translations")
  Translator.Result translate(@RequestBody Translator.Request request) {
    return translator.translate(request);
  }

  /** The desk shows {@code detail}, so a missing key or a failed call reads as a sentence. */
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  org.springframework.http.ResponseEntity<java.util.Map<String, String>> failed(
      org.springframework.web.server.ResponseStatusException e) {
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .body(java.util.Map.of("detail", e.getReason() == null ? "Translation failed." : e.getReason()));
  }
}
