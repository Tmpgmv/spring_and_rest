package com.example.hotel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotel.config.TraceIdFilter;
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
   * Исходные данные: Аутентифицированный тестовый клиент отправляет пустое имя в POST
   * /test/fault/validation без заголовка трассировки.
   * Ожидаемый результат: Ответ 400 содержит JSON ErrorDTO с временем, статусом, сообщением, путём, пустым
   * traceId и единственной ошибкой name: must not be blank.
   */
  @DisplayName("Ошибка валидации возвращает полный ErrorDTO с ошибкой поля")
  @Test
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
   * Исходные данные: Параметры code и reasonPhrase из CsvSource задают HTTP-статус 401, 403, 409 или 503 и
   * его описание; GET /test/fault/status/{code} выбрасывает соответствующее исключение.
   * Ожидаемый результат: Ответ сохраняет code, error=reasonPhrase, сообщение fault с кодом, время и
   * исходный путь; traceId пуст, fieldErrors отсутствует.
   */
  @DisplayName("Статус ResponseStatusException сохраняется в ErrorDTO")
  @ParameterizedTest(name = "[{index}] {arguments}")
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
   * Исходные данные: Аутентифицированный тестовый клиент запрашивает /no-such-path без заголовка
   * трассировки.
   * Ожидаемый результат: Ответ 404 содержит Not Found, время, сообщение, исходный путь и пустой traceId;
   * fieldErrors отсутствует.
   */
  @DisplayName("Неизвестный путь возвращает ErrorDTO со статусом 404")
  @Test
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
   * Исходные данные: GET /test/fault/boom выбрасывает RuntimeException с сообщением secret detail;
   * заголовка трассировки нет.
   * Ожидаемый результат: Ответ 500 содержит Internal server error, время, исходный путь и пустой traceId
   * без fieldErrors; строка secret detail не попадает в тело.
   */
  @DisplayName("Необработанное исключение возвращает 500 без внутренних подробностей")
  @Test
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
   * Исходные данные: Клиент запрашивает /test/fault/status/409 с X-Trace-Id=abc.
   * Ожидаемый результат: Ответ 409 содержит traceId=abc.
   */
  @DisplayName("Заголовок трассировки переносится в ErrorDTO")
  @Test
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
