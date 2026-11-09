package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.entity.UserRole;
import com.example.booking.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Проверяет регистрацию: успешный ответ 201 содержит только токен с подписью HS256, проверяемой
 * настроенным секретом, и полями sub=alice, role=USER, exp-iat=3600 с. Запись хранит хеш bcrypt
 * вместо открытого пароля. Повторное имя даёт 409 с ErrorDTO; пустые и отсутствующие поля дают 400
 * с ошибкой для каждого поля. Начальные данные data.sql должны загрузить демонстрационные учётные
 * записи ADMIN и USER, хеши которых соответствуют документированным паролям; незаметный пропуск
 * загрузки недопустим.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class UserRegistrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  /*
   * Исходные данные: Контекст приложения загружает data.sql; выбираются бронирования пользователя user и
   * связанные обработанные запросы.
   * Ожидаемый результат: Есть две записи: номер 1 со статусом CONFIRMED и номер 4 со статусом CANCELLED;
   * сохранены HTTP 201 и снимки с совпадающими requestId, номерами и датами.
   */
  @Test
  @DisplayName("Начальные бронирования содержат сохранённые результаты запросов")
  void seedLoadsTerminalBookingsWithReplayAndCancellationMetadata() throws Exception {
    var samples =
        jdbc.queryForList(
            """
                select b.room_id, b.status, b.start_date, b.end_date,
                       p.request_id, p.http_status, p.response_snapshot
                from bookings b join users u on u.id = b.user_id
                join processed_requests p on p.booking_id = b.id
                where u.username = 'user' order by b.id
                """);
    assertThat(samples).hasSize(2);
    assertThat(samples.get(0))
        .containsEntry("ROOM_ID", 1L)
        .containsEntry("STATUS", "CONFIRMED")
        .containsEntry("REQUEST_ID", "demo-confirmed-001");
    assertThat(samples.get(1))
        .containsEntry("ROOM_ID", 4L)
        .containsEntry("STATUS", "CANCELLED")
        .containsEntry("REQUEST_ID", "demo-cancelled-001");
    for (var sample : samples) {
      assertThat(sample.get("HTTP_STATUS")).isEqualTo(201);
      var snapshot = objectMapper.readTree((String) sample.get("RESPONSE_SNAPSHOT"));
      assertThat(snapshot.get("requestId").asText()).isEqualTo(sample.get("REQUEST_ID"));
      assertThat(snapshot.get("roomId").asLong()).isEqualTo(sample.get("ROOM_ID"));
      assertThat(snapshot.get("startDate").asText()).isEqualTo(sample.get("START_DATE").toString());
      assertThat(snapshot.get("endDate").asText()).isEqualTo(sample.get("END_DATE").toString());
    }
  }

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  private String jwtSecret;

  /*
   * Исходные данные: Пользователь alice отсутствует; POST /user/register содержит имя alice и пароль s3cret.
   * Ожидаемый результат: Ответ 201 содержит только JWT с подписью HS256, sub=alice, ролью USER и сроком 3600
   * секунд; пользователь сохранён с идентификатором и подходящим хешем bcrypt.
   */
  @Test
  @DisplayName("Регистрация нового пользователя сохраняет bcrypt и выдаёт подписанный токен")
  void happyRegisterReturns201WithHs256TokenAndPersistsBcryptRow() throws Exception {
    // Дано: пользователя alice нет.
    assertThat(userRepository.existsByUsername("alice")).isFalse();

    // Действие: регистрация с допустимыми учётными данными.
    MvcResult result =
        mockMvc
            .perform(
                post("/user/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"alice\",\"password\":\"s3cret\"}"))
            // Результат: 201 с полем token в теле.
            .andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn();

    // Токен проверяется алгоритмом HS256 с настроенным секретом и содержит
    // ожидаемые поля (sub=alice, role=USER, exp-iat = 3600 с).
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.size()).as("response body must carry only the token").isEqualTo(1);
    SignedJWT jwt = SignedJWT.parse(body.get("token").asText());
    assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(jwtSecret))))
        .as("HS256 signature verifies under the configured secret")
        .isTrue();
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo("alice");
    assertThat(claims.getClaim("role")).isEqualTo("USER");
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
        .isEqualTo(3600_000L);

    // Запись сохранена с хешем bcrypt и ролью USER.
    var persisted = userRepository.findByUsername("alice");
    assertThat(persisted).isPresent();
    assertThat(persisted.get().getId()).isNotNull();
    assertThat(persisted.get().getRole()).isEqualTo(UserRole.USER);
    assertThat(persisted.get().getPassword()).startsWith("$2");
    assertThat(passwordEncoder.matches("s3cret", persisted.get().getPassword())).isTrue();
  }

  /*
   * Исходные данные: Сначала регистрируется bob с паролем hunter2, затем повторно с паролем other.
   * Ожидаемый результат: Первая регистрация даёт 201, повторная — ErrorDTO со статусом 409 без fieldErrors;
   * исходный пароль hunter2 продолжает соответствовать хешу.
   */
  @Test
  @DisplayName("Повторная регистрация имени возвращает конфликт и сохраняет исходный пароль")
  void duplicateUsernameReturns409WithErrorDto() throws Exception {
    // Дано: bob уже зарегистрирован.
    mockMvc
        .perform(
            post("/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\",\"password\":\"hunter2\"}"))
        .andExpect(status().isCreated());

    // Повторная регистрация bob возвращает 409 с общей структурой ErrorDTO.
    mockMvc
        .perform(
            post("/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bob\",\"password\":\"other\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.path").value("/user/register"))
        .andExpect(jsonPath("$.traceId").value(""))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());

    // Отклонённая попытка не должна менять хеш первой учётной записи.
    assertThat(
            passwordEncoder.matches(
                "hunter2", userRepository.findByUsername("bob").orElseThrow().getPassword()))
        .isTrue();
  }

  /*
   * Исходные данные: POST /user/register содержит username из двух пробелов и пустой password.
   * Ожидаемый результат: Ответ 400 содержит ErrorDTO с путём регистрации и двумя ошибками полей username и
   * password.
   */
  @Test
  @DisplayName("Пустые имя и пароль при регистрации дают две ошибки полей")
  void blankUsernameAndPasswordReturn400WithFieldErrors() throws Exception {
    mockMvc
        .perform(
            post("/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"  \",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.path").value("/user/register"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(2))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'username')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
  }

  /*
   * Исходные данные: POST /user/register содержит пустой JSON-объект.
   * Ожидаемый результат: Ответ 400 содержит две ошибки полей username и password.
   */
  @Test
  @DisplayName("Отсутствующие имя и пароль при регистрации дают две ошибки полей")
  void missingUsernameAndPasswordReturn400WithFieldErrors() throws Exception {
    mockMvc
        .perform(post("/user/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.length()").value(2))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'username')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
  }

  /*
   * Исходные данные: Контекст приложения загружает демонстрационные записи admin и user из data.sql.
   * Ожидаемый результат: Обе записи существуют, имеют роли ADMIN и USER и хеши bcrypt, соответствующие
   * паролям admin123 и user123.
   */
  @Test
  @DisplayName("Начальные учётные записи имеют нужные роли и хеши паролей")
  void seedLoadsDemoAdminAndUserAccountsWithMatchingBcryptHashes() {
    // В новом контексте data.sql должен вставить обе демонстрационные учётные записи;
    // отсутствие начальных данных приводит к ошибке, незаметный пропуск недопустим.
    var admin = userRepository.findByUsername("admin");
    var user = userRepository.findByUsername("user");

    assertThat(admin).isPresent();
    assertThat(admin.get().getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(admin.get().getPassword()).startsWith("$2");
    assertThat(passwordEncoder.matches("admin123", admin.get().getPassword())).isTrue();

    assertThat(user).isPresent();
    assertThat(user.get().getRole()).isEqualTo(UserRole.USER);
    assertThat(user.get().getPassword()).startsWith("$2");
    assertThat(passwordEncoder.matches("user123", user.get().getPassword())).isTrue();
  }
}
