package com.example.hotel.config;

import com.example.hotel.dto.ErrorDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Проверяет служебные операции независимо от пользовательских JWT; зарегистрирован только во
 * внутренней цепочке безопасности.
 */
public class InternalKeyFilter extends OncePerRequestFilter {
  private final byte[] expectedKey;
  private final ObjectMapper mapper;

  public InternalKeyFilter(String key, ObjectMapper mapper) {
    if (key.isBlank()) throw new IllegalArgumentException("Internal key must not be blank");
    this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String key = request.getHeader("X-Internal-Key");
    if (key == null || !MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8))) {
      int status = key == null ? 401 : 403;
      response.setStatus(status);
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorDTO(
              Instant.now(),
              status,
              status == 401 ? "Unauthorized" : "Forbidden",
              "Invalid internal credentials",
              request.getRequestURI(),
              MDC.get("traceId") == null ? "" : MDC.get("traceId"),
              null));
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
    SecurityContextHolder.setContext(context);
    chain.doFilter(request, response);
  }
}
