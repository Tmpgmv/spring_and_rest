package com.example.booking.service;

import com.example.booking.entity.UserRole;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Выпускает JWT HS256 со сроком действия один час. Поля: sub — имя пользователя, role — {@link
 * UserRole}, iat — текущее время, exp — iat + ttl. Срок берётся из auth.jwt.ttl-seconds (начальное
 * значение 3600), без константы в коде. Общий секрет HMAC настроен в SecurityConfig; одинаковое
 * значение проверяет токен во всех сервисах.
 */
@Service
public class JwtTokenService {

  private final JwtEncoder jwtEncoder;
  private final long ttlSeconds;

  public JwtTokenService(JwtEncoder jwtEncoder, @Value("${auth.jwt.ttl-seconds}") long ttlSeconds) {
    this.jwtEncoder = jwtEncoder;
    this.ttlSeconds = ttlSeconds;
  }

  /** Кодирует новый токен для заданных субъекта и роли. */
  public String issueToken(String username, UserRole role) {
    Instant issuedAt = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("booking-service")
            .subject(username)
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(ttlSeconds))
            .claim("role", role.name())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
