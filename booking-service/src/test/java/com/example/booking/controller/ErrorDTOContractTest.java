package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.booking.config.TraceIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Проверяет единый контракт ErrorDTO: timestamp, status, error, message, path, traceId и
 * необязательный fieldErrors[]. Валидация возвращает 400 с fieldErrors, статусы
 * ResponseStatusException сохраняются, неизвестный путь даёт 404 ErrorDTO вместо тела Boot,
 * необработанная ошибка — 500 без внутренних подробностей. traceId читается из MDC: значение
 * заголовка шлюза либо пустая строка, без локальной генерации. Аналогичный тест другого сервиса
 * закрепляет ту же структуру.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@org.springframework.security.test.context.support.WithMockUser
@AutoConfigureMockMvc
// Регистрируем явно: тестовые точки ошибок должны присутствовать в общем контексте приложения
// независимо от особенностей сканирования компонентов в тестовом пути классов.
@Import(ErrorDTOContractTest.FaultController.class)
class ErrorDTOContractTest {

  @Autowired private MockMvc mockMvc;

  /*
   * Исходные данные: Авторизованный тестовый пользователь отправляет POST /test/fault/validation с пустым
   * name.
   * Ожидаемый результат: Ответ 400 имеет JSON-контракт ErrorDTO с пустым traceId и одной ошибкой поля name с
   * сообщением must not be blank.
   */
  @Test
  @DisplayName("Ошибка валидации возвращает ErrorDTO с описанием поля")
  void validationFailureReturns400WithErrorDtoAndPopulatedFieldErrors() throws Exception {
    mockMvc
        .perform(
            post("/test/fault/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.path").value("/test/fault/validation"))
        .andExpect(jsonPath("$.traceId").value(""))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"));
  }

  /*
   * Исходные данные: Параметры code и reasonPhrase задают пары 401/Unauthorized, 403/Forbidden, 409/Conflict
   * и 503/Service Unavailable; тестовая точка бросает ResponseStatusException.
   * Ожидаемый результат: Каждая пара сохраняется в HTTP-статусе и полях status/error; message равен fault с
   * кодом, путь соответствует запросу, traceId пуст, fieldErrors отсутствует.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Статус исключения сохраняется в полном ErrorDTO")
  @CsvSource({"401,Unauthorized", "403,Forbidden", "409,Conflict", "503,Service Unavailable"})
  void responseStatusExceptionPassesStatusThroughWithFullErrorDto(int code, String reasonPhrase)
      throws Exception {
    mockMvc
        .perform(get("/test/fault/status/" + code))
        .andExpect(status().is(code))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.status").value(code))
        .andExpect(jsonPath("$.error").value(reasonPhrase))
        .andExpect(jsonPath("$.message").value("fault " + code))
        .andExpect(jsonPath("$.path").value("/test/fault/status/" + code))
        .andExpect(jsonPath("$.traceId").value(""))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }

  /*
   * Исходные данные: Авторизованный тестовый пользователь выполняет GET /no-such-path без заголовка
   * трассировки.
   * Ожидаемый результат: Ответ 404 содержит время, status=404, error=Not Found, сообщение и исходный путь;
   * traceId пуст, fieldErrors отсутствует.
   */
  @Test
  @DisplayName("Неизвестный путь возвращает единый ErrorDTO 404")
  void unknownPathReturns404WithErrorDtoBody() throws Exception {
    mockMvc
        .perform(get("/no-such-path"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.path").value("/no-such-path"))
        .andExpect(jsonPath("$.traceId").value(""))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }

  /*
   * Исходные данные: GET /test/fault/boom вызывает RuntimeException с внутренним сообщением secret detail.
   * Ожидаемый результат: Ответ 500 содержит ErrorDTO с общим сообщением Internal server error, исходным путём
   * и пустым traceId; fieldErrors и secret detail отсутствуют.
   */
  @Test
  @DisplayName("Необработанное исключение не раскрывает внутреннее сообщение клиенту")
  void unhandledExceptionReturns500WithGenericMessageAndNoInternals() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/test/fault/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("Internal Server Error"))
            .andExpect(jsonPath("$.message").value("Internal server error"))
            .andExpect(jsonPath("$.path").value("/test/fault/boom"))
            .andExpect(jsonPath("$.traceId").value(""))
            .andExpect(jsonPath("$.fieldErrors").doesNotExist())
            .andReturn();

    // Сообщение выброшенного исключения не должно попадать клиенту.
    assertThat(result.getResponse().getContentAsString()).doesNotContain("secret detail");
  }

  /*
   * Исходные данные: GET /test/fault/status/409 содержит заголовок X-Trace-Id со значением abc.
   * Ожидаемый результат: Ответ имеет статус 409 и traceId=abc.
   */
  @Test
  @DisplayName("Идентификатор трассировки из заголовка попадает в ErrorDTO")
  void traceIdFromGatewayHeaderIsCarriedIntoErrorDto() throws Exception {
    mockMvc
        .perform(get("/test/fault/status/409").header(TraceIdFilter.TRACE_HEADER, "abc"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.traceId").value("abc"));
  }

  /**
   * Тестовые конечные точки ошибок позволяют проверить обработчики на настоящих MVC-запросах. Они
   * не входят в публичный интерфейс сервиса.
   */
  @RestController
  static class FaultController {

    @PostMapping("/test/fault/validation")
    void validation(@Valid @RequestBody ValidationBody body) {}

    @GetMapping("/test/fault/status/{code}")
    void status(@PathVariable int code) {
      throw new ResponseStatusException(HttpStatusCode.valueOf(code), "fault " + code);
    }

    @GetMapping("/test/fault/boom")
    void boom() {
      throw new RuntimeException("secret detail");
    }

    record ValidationBody(@NotBlank String name) {}
  }
}
