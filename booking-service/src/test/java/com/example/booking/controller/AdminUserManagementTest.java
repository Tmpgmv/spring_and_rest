package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.booking.entity.User;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.UserRepository;
import com.example.booking.service.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Transactional
class AdminUserManagementTest {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired JwtTokenService tokens;
  @Autowired ObjectMapper mapper;
  @Autowired EntityManager entityManager;

  String admin() {
    return "Bearer " + tokens.issueToken("admin", UserRole.ADMIN);
  }

  User fixture() {
    return users.saveAndFlush(
        new User("managed-user", passwords.encode("original-password"), UserRole.USER));
  }

  /*
   * Исходные данные: Параметр role перебирает USER и ADMIN; администратор создаёт created-user с паролем
   * secret.
   * Ожидаемый результат: Для каждой роли ответ 201 содержит идентификатор, имя и роль без пароля; хеш bcrypt
   * соответствует secret, а вход новой записи даёт 200 и токен.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Администратор создаёт пользователя с заданной ролью и хешем bcrypt")
  @EnumSource(UserRole.class)
  void adminCreatesAccountWithRequestedRoleAndBcryptPassword(UserRole role) throws Exception {
    mvc.perform(
            post("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"created-user\",\"password\":\"secret\",\"role\":\""
                        + role
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.username").value("created-user"))
        .andExpect(jsonPath("$.role").value(role.name()))
        .andExpect(jsonPath("$.password").doesNotExist());
    entityManager.clear();
    User stored = users.findByUsername("created-user").orElseThrow();
    assertThat(stored.getRole()).isEqualTo(role);
    assertThat(stored.getPassword()).startsWith("$2").isNotEqualTo("secret");
    assertThat(passwords.matches("secret", stored.getPassword())).isTrue();
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"created-user\",\"password\":\"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  /*
   * Исходные данные: Есть managed-user с ролью USER и паролем original-password; администратор меняет пароль
   * на changed-password и роль на ADMIN.
   * Ожидаемый результат: Ответ 200 сохраняет идентификатор и скрывает пароль; запись имеет новую роль и хеш,
   * новый вход даёт токен ADMIN, а старый пароль — 401.
   */
  @Test
  @DisplayName("Изменение пароля и роли применяется при следующем входе")
  void adminUpdatesPasswordAndRoleAndNewCredentialsWork() throws Exception {
    Long id = fixture().getId();
    mvc.perform(
            patch("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"managed-user\",\"password\":\"changed-password\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.password").doesNotExist());
    entityManager.flush();
    entityManager.clear();
    User stored = users.findByUsername("managed-user").orElseThrow();
    assertThat(stored.getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(passwords.matches("changed-password", stored.getPassword())).isTrue();
    var login =
        mvc.perform(
                post("/user/auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"managed-user\",\"password\":\"changed-password\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String token = mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
    assertThat(com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("role"))
        .isEqualTo("ADMIN");
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"managed-user\",\"password\":\"original-password\"}"))
        .andExpect(status().isUnauthorized());
  }

  /*
   * Исходные данные: Есть managed-user с исходным хешем; администратор сначала меняет только роль на ADMIN,
   * затем только пароль на replacement.
   * Ожидаемый результат: Оба запроса дают 200; после первого хеш неизменен, после второго роль остаётся ADMIN
   * и новый пароль соответствует хешу.
   */
  @Test
  @DisplayName("Частичное обновление сохраняет пропущенные поля пользователя")
  void partialUpdatesPreserveOmittedFields() throws Exception {
    String hash = fixture().getPassword();
    mvc.perform(
            patch("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"managed-user\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isOk());
    assertThat(users.findByUsername("managed-user").orElseThrow().getPassword()).isEqualTo(hash);
    mvc.perform(
            patch("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"managed-user\",\"password\":\"replacement\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
    assertThat(
            passwords.matches(
                "replacement", users.findByUsername("managed-user").orElseThrow().getPassword()))
        .isTrue();
  }

  /*
   * Исходные данные: Существует managed-user с паролем original-password; администратор выполняет DELETE
   * /user по имени.
   * Ожидаемый результат: Удаление даёт пустой ответ 204; запись отсутствует в хранилище, а вход со старым
   * паролем возвращает 401.
   */
  @Test
  @DisplayName("Удалённый администратором пользователь больше не может войти")
  void adminDeletesAccountAndItCanNoLongerAuthenticate() throws Exception {
    fixture();
    mvc.perform(delete("/user").param("username", "managed-user").header("Authorization", admin()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    entityManager.flush();
    entityManager.clear();
    assertThat(users.findByUsername("managed-user")).isEmpty();
    mvc.perform(
            post("/user/auth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"managed-user\",\"password\":\"original-password\"}"))
        .andExpect(status().isUnauthorized());
  }

  /*
   * Исходные данные: Есть managed-user с ролью USER; параметр method перебирает POST, PATCH и DELETE, каждый
   * запрос выполняется анонимно и с токеном USER.
   * Ожидаемый результат: Анонимный запрос даёт 401, пользовательский — 403 с ErrorDTO и traceId=admin-denied;
   * число записей, роль и исходный пароль сохраняются.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Управление пользователями доступно только администратору")
  @ValueSource(strings = {"POST", "PATCH", "DELETE"})
  void ordinaryUserAndAnonymousCallerCannotManageAccounts(String method) throws Exception {
    User existing = fixture();
    long count = users.count();
    for (boolean authenticated : new boolean[] {false, true}) {
      var request =
          request(HttpMethod.valueOf(method), "/user")
              .param("username", "managed-user")
              .contentType(MediaType.APPLICATION_JSON)
              .content(
                  "{\"username\":\"managed-user\",\"password\":\"hacked\",\"role\":\"ADMIN\"}");
      if (authenticated)
        request.header("Authorization", "Bearer " + tokens.issueToken("user", UserRole.USER));
      int expected = authenticated ? 403 : 401;
      mvc.perform(request.header("X-Trace-Id", "admin-denied"))
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.status").value(expected))
          .andExpect(jsonPath("$.error").value(authenticated ? "Forbidden" : "Unauthorized"))
          .andExpect(jsonPath("$.path").value("/user"))
          .andExpect(jsonPath("$.traceId").value("admin-denied"));
    }
    assertThat(users.count()).isEqualTo(count);
    assertThat(users.findById(existing.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    assertThat(passwords.matches("original-password", existing.getPassword())).isTrue();
  }

  /*
   * Исходные данные: Параметр method задаёт PATCH либо DELETE; администратор обращается к absent-user.
   * Ожидаемый результат: Оба метода возвращают 404 со статусом 404 и сообщением User not found.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Изменение и удаление отсутствующего пользователя дают 404")
  @ValueSource(strings = {"PATCH", "DELETE"})
  void unknownUsernameReturns404(String method) throws Exception {
    mvc.perform(
            request(HttpMethod.valueOf(method), "/user")
                .param("username", "absent-user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"absent-user\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("User not found"));
  }

  /*
   * Исходные данные: Существует managed-user с ролью USER; администратор пытается создать одноимённую запись
   * с ролью ADMIN.
   * Ожидаемый результат: Ответ содержит HTTP 409 и status=409; существующая запись сохраняет роль USER.
   */
  @Test
  @DisplayName("Создание пользователя с занятым именем возвращает конфликт")
  void duplicateCreateReturns409AndPreservesExistingAccount() throws Exception {
    fixture();
    mvc.perform(
            post("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"managed-user\",\"password\":\"replacement\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));
    assertThat(users.findByUsername("managed-user").orElseThrow().getRole())
        .isEqualTo(UserRole.USER);
  }

  /*
   * Исходные данные: Параметр body перебирает пустой объект, пустое имя, пустой пароль и отсутствие роли;
   * запрос POST /user авторизован администратором.
   * Ожидаемый результат: Каждый набор возвращает 400 и массив fieldErrors.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Создание пользователя с неполными данными возвращает ошибки валидации")
  @ValueSource(
      strings = {
        "{}",
        "{\"username\":\" \",\"password\":\"secret\",\"role\":\"USER\"}",
        "{\"username\":\"name\",\"password\":\" \",\"role\":\"USER\"}",
        "{\"username\":\"name\",\"password\":\"secret\"}"
      })
  void invalidCreateReturnsValidationErrors(String body) throws Exception {
    mvc.perform(
            post("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  /*
   * Исходные данные: Есть managed-user; параметр body задаёт пустой объект либо имя managed-user с паролем из
   * пробела для PATCH /user от администратора.
   * Ожидаемый результат: Оба набора возвращают 400 и массив fieldErrors.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Неполное обновление пользователя отклоняется валидацией")
  @ValueSource(strings = {"{}", "{\"username\":\"managed-user\",\"password\":\" \"}"})
  void invalidPatchReturnsValidationErrorsWithoutChangingAccount(String body) throws Exception {
    fixture();
    mvc.perform(
            patch("/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  /*
   * Исходные данные: Параметр method задаёт POST либо PATCH; администратор передаёт имя someone, пароль
   * secret и роль SUPERADMIN.
   * Ожидаемый результат: Каждый метод возвращает ErrorDTO со статусом 400 и сообщением Invalid request body.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Неизвестная роль в запросе управления пользователем отклоняется")
  @ValueSource(strings = {"POST", "PATCH"})
  void invalidRoleReturns400ErrorDto(String method) throws Exception {
    mvc.perform(
            request(HttpMethod.valueOf(method), "/user")
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"someone\",\"password\":\"secret\",\"role\":\"SUPERADMIN\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Invalid request body"));
  }
}
