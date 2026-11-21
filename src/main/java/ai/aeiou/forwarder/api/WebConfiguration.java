package ai.aeiou.forwarder.api;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public WebConfiguration(@Value("${UI_ORIGINS:}") String configured) {
    origins =
        Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (origins.length > 0)
      registry
          .addMapping("/api/v1/**")
          .allowedOrigins(origins)
          .allowedMethods("GET", "POST", "OPTIONS")
          .allowedHeaders("Authorization", "Content-Type", "Last-Event-ID", "Idempotency-Key")
          .exposedHeaders("Content-Disposition");
  }
}
