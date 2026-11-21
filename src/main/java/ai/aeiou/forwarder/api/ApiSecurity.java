package ai.aeiou.forwarder.api;

import static ai.aeiou.forwarder.domain.Json.*;

import ai.aeiou.forwarder.application.TimelineClock;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ApiSecurity extends OncePerRequestFilter {
  final String token;

  ApiSecurity(@Value("${forwarder.ui-token}") String token, TimelineClock clock) {
    if (clock.live && token.isBlank())
      throw new IllegalArgumentException("UI_TOKEN required for live API");
    this.token = token.isBlank() ? "demo" : token;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (req.getRequestURI().startsWith("/api/v1/") && !req.getMethod().equals("OPTIONS")) {
      String auth = req.getHeader("Authorization");
      if (auth == null
          || !MessageDigest.isEqual(
              ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
              auth.getBytes(StandardCharsets.UTF_8))) {
        int status = auth == null ? 401 : 403;
        res.setStatus(status);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/problem+json");
        res.getWriter()
            .write(
                obj(
                        "type",
                        "about:blank",
                        "title",
                        "Access denied",
                        "status",
                        status,
                        "detail",
                        "Supply the team UI bearer credential",
                        "code",
                        "ACCESS_DENIED",
                        "requestId",
                        UUID.randomUUID().toString())
                    .toString());
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
