package com.example.hotel.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Import(JwtSecurityTest.ProtectedController.class)
class JwtSecurityTest {
  @Autowired MockMvc mvc;

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  String secret;

  // Настоящие токены HS256 проверяют декодер, а не подменённый SecurityContext.
  String token(String role, Instant expiry, boolean wrongKey) throws Exception {
    byte[] key = Base64.getDecoder().decode(secret);
    if (wrongKey) key[0] ^= 1;
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("booking-service")
                .subject("user")
                .claim("role", role)
                .issueTime(Date.from(expiry.minusSeconds(3600)))
                .expirationTime(Date.from(expiry))
                .build());
    jwt.sign(new MACSigner(key));
    return jwt.serialize();
  }

  /*
   * Исходные данные: Созданы настоящие HS256-токены USER и ADMIN сроком на час; USER обращается к
   * пользовательскому и административному методам, ADMIN — к административному; отказу задан traceId
   * security-test.
   * Ожидаемый результат: USER получает 200 с телом ok и 403 Access denied на административном методе с
   * полным ErrorDTO и исходной трассировкой; ADMIN получает 200.
   */
  @DisplayName("Подписанный JWT разрешает только методы своей роли")
  @Test
  void validTokenIsAcceptedDirectlyAndRoleClaimControlsMethods() throws Exception {
    String user = token("USER", Instant.now().plusSeconds(3600), false);
    mvc.perform(get("/test/security/user").header("Authorization", "Bearer " + user))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
    mvc.perform(
            get("/test/security/admin")
                .header("Authorization", "Bearer " + user)
                .header("X-Trace-Id", "security-test"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"))
        .andExpect(jsonPath("$.traceId").value("security-test"))
        .andExpect(jsonPath("$.path").value("/test/security/admin"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.message").value("Access denied"));
    mvc.perform(
            get("/test/security/admin")
                .header(
                    "Authorization",
                    "Bearer " + token("ADMIN", Instant.now().plusSeconds(3600), false)))
        .andExpect(status().isOk());
  }

  /*
   * Исходные данные: GET /test/security/user вызывается с пустой авторизацией, Bearer invalid, просроченным
   * токеном и токеном с другим ключом; traceId=bad-token.
   * Ожидаемый результат: Каждый ответ 401 содержит WWW-Authenticate: Bearer и ErrorDTO с временем, исходным
   * путём и traceId bad-token.
   */
  @DisplayName("Отсутствующий, повреждённый, просроченный и неверно подписанный JWT возвращают 401")
  @Test
  void missingMalformedExpiredAndWrongSignatureReturnErrorDto() throws Exception {
    for (String value :
        new String[] {
          "",
          "Bearer invalid",
          "Bearer " + token("USER", Instant.now().minusSeconds(300), false),
          "Bearer " + token("USER", Instant.now().plusSeconds(3600), true)
        }) {
      mvc.perform(
              get("/test/security/user")
                  .header("Authorization", value)
                  .header("X-Trace-Id", "bad-token"))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("WWW-Authenticate", "Bearer"))
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.error").value("Unauthorized"))
          .andExpect(jsonPath("$.path").value("/test/security/user"))
          .andExpect(jsonPath("$.timestamp").exists())
          .andExpect(jsonPath("$.traceId").value("bad-token"));
    }
  }

  /*
   * Исходные данные: Без токена выполняются GET /unknown, /user/register и /user/auth.
   * Ожидаемый результат: Каждый запрос возвращает 401.
   */
  @DisplayName("Неизвестные пути и непубличные методы требуют аутентификацию")
  @Test
  void unknownPathsAndNonPublicMethodsRequireAuthentication() throws Exception {
    for (String path : new String[] {"/unknown", "/user/register", "/user/auth"}) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
  }

  @RestController
  static class ProtectedController {
    @GetMapping("/test/security/user")
    @PreAuthorize("hasRole('USER')")
    public String user() {
      return "ok";
    }

    @GetMapping("/test/security/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin() {
      return "ok";
    }
  }
}
