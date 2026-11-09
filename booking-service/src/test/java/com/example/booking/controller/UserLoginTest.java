package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class UserLoginTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  String secret;

  /*
   * Исходные данные: Параметр body задаёт JSON без обязательных полей либо с пустым username или password;
   * передаётся X-Trace-Id=invalid-login.
   * Ожидаемый результат: Каждый вариант возвращает 400 с непустым fieldErrors, путём /user/auth и
   * traceId=invalid-login; токен отсутствует.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Отсутствующие или пустые данные входа отклоняются с ошибками полей")
  @ValueSource(
      strings = {
        "{}",
        "{\"username\":\"user\"}",
        "{\"password\":\"user123\"}",
        "{\"username\":\" \",\"password\":\"user123\"}",
        "{\"username\":\"user\",\"password\":\" \"}"
      })
  void missingOrBlankCredentialsReturn400WithFieldErrors(String body) throws Exception {
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Trace-Id", "invalid-login")
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.path").value("/user/auth"))
        .andExpect(jsonPath("$.traceId").value("invalid-login"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  /*
   * Исходные данные: Параметр body принимает повреждённый JSON с открывающей скобкой либо пустую строку.
   * Ожидаемый результат: Оба варианта возвращают HTTP 400 со статусом 400 в теле и без token.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Пустое или повреждённое тело входа отклоняется без токена")
  @ValueSource(strings = {"{", ""})
  void unreadableLoginBodyReturns400WithoutToken(String body) throws Exception {
    mvc.perform(post("/user/auth").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  /*
   * Исходные данные: Параметры username, password и role задают пары user/user123/USER и admin/admin123/ADMIN
   * для POST /user/auth.
   * Ожидаемый результат: Каждая пара даёт 200 и только JWT с верной подписью HS256, указанными именем и
   * ролью, текущим временем выпуска и сроком 3600 секунд.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Вход демонстрационного пользователя выдаёт подписанный часовой токен")
  @CsvSource({"user,user123,USER", "admin,admin123,ADMIN"})
  void publicLoginReturnsFreshSignedOneHourToken(String username, String password, String role)
      throws Exception {
    Instant before = Instant.now().minusSeconds(1);
    var response =
        mvc.perform(
                post("/user/auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            java.util.Map.of("username", username, "password", password))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();
    var body = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(body.size()).isEqualTo(1);
    var jwt = SignedJWT.parse(body.get("token").asText());
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
    assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(secret)))).isTrue();
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo(username);
    assertThat(claims.getStringClaim("role")).isEqualTo(role);
    assertThat(claims.getIssueTime().toInstant()).isBetween(before, Instant.now());
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
        .isEqualTo(3600000L);
  }

  /*
   * Исходные данные: Параметры username и password задают user/wrong либо unknown-login-user/wrong.
   * Ожидаемый результат: Оба варианта возвращают ErrorDTO 401 с сообщением Invalid credentials, путём
   * /user/auth, временем и traceId; токен отсутствует.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Неверный пароль и неизвестное имя дают одинаковое сообщение об отказе")
  @CsvSource({"user,wrong", "unknown-login-user,wrong"})
  void invalidCredentialsReturnGenericErrorDto(String username, String password) throws Exception {
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of("username", username, "password", password))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid credentials"))
        .andExpect(jsonPath("$.path").value("/user/auth"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.traceId").exists())
        .andExpect(jsonPath("$.token").doesNotExist());
  }
}
