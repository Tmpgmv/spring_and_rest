package com.example.booking.config;

import com.example.booking.dto.ErrorDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Проверка JWT без серверной сессии и контроль ролей на уровне методов. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("role");
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    var entryPoint = authenticationEntryPoint(mapper);
    var deniedHandler = accessDeniedHandler(mapper);
    http.csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**").disable())
        .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/h2-console/**")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/user/register", "/user/auth")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(deniedHandler));
    return http.build();
  }

  private org.springframework.security.web.AuthenticationEntryPoint authenticationEntryPoint(
      ObjectMapper mapper) {
    return (request, response, exception) -> {
      response.setStatus(401);
      response.setHeader("WWW-Authenticate", "Bearer");
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorDTO(
              Instant.now(),
              401,
              "Unauthorized",
              "Authentication required",
              request.getRequestURI(),
              MDC.get("traceId") == null ? "" : MDC.get("traceId"),
              null));
    };
  }

  private org.springframework.security.web.access.AccessDeniedHandler accessDeniedHandler(
      ObjectMapper mapper) {
    return (request, response, exception) -> {
      response.setStatus(403);
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          new ErrorDTO(
              Instant.now(),
              403,
              "Forbidden",
              "Access denied",
              request.getRequestURI(),
              MDC.get("traceId") == null ? "" : MDC.get("traceId"),
              null));
    };
  }

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.secret}") String secret) {
    return NimbusJwtDecoder.withSecretKey(
            new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Ключ подписи HMAC: настроенный секрет Base64 декодируется в исходные байты и оборачивается в
   * симметричный JWK, чтобы {@link NimbusJwtEncoder} подписывал токены HS256.
   */
  @Bean
  JwtEncoder jwtEncoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.secret}") String secret) {
    byte[] keyBytes = Base64.getDecoder().decode(secret);
    OctetSequenceKey jwk =
        new OctetSequenceKey.Builder(keyBytes).algorithm(JWSAlgorithm.HS256).build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk)));
  }
}
