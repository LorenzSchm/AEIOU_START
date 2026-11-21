package ai.aeiou.dispatch;

import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/**
 * Applies the {@link WriterLease} to everything that can change data: every scheduled job (mail
 * polling, the wave, bookings, follow-ups, board results and the Postgres mirror) and every
 * non-GET API request, including the mailbox webhook.
 */
@Configuration
class WriteGuard implements WebMvcConfigurer {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final WriterLease lease;

  WriteGuard(WriterLease lease) {
    this.lease = lease;
  }

  @Bean
  ThreadPoolTaskSchedulerCustomizer writerOnlyScheduling() {
    return scheduler -> scheduler.setTaskDecorator(task -> () -> {
      if (lease.mayWrite()) task.run();
    });
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
          throws Exception {
        if (SAFE.contains(request.getMethod()) || lease.mayWrite()) return true;
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(lease.readOnly()
            ? "{\"detail\":\"This desk is read-only.\"}"
            : "{\"detail\":\"Another worker holds the writer lease.\"}");
        return false;
      }
    }).addPathPatterns("/api/**");
  }
}
