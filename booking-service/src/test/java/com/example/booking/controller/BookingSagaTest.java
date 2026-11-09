package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.ProcessedRequestRepository;
import com.example.booking.saga.BookingSaga;
import com.example.booking.saga.BookingTransactions;
import com.example.booking.service.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "auth.internal.key=test-saga-key",
      "hotel.client.read-timeout=200ms",
      "hotel.client.connect-timeout=200ms",
      "hotel.client.resilience.max-attempts=1",
      "hotel.client.resilience.window-size=1000",
      "hotel.client.resilience.minimum-calls=1000",
      "booking.replay.wait-timeout=2s",
      "booking.replay.poll-interval=10ms"
    })
@AutoConfigureMockMvc
@Import(BookingSagaTest.FixedTime.class)
class BookingSagaTest {
  static final AtomicReference<String> RECOMMENDATIONS =
      new AtomicReference<>("[{\"id\":12},{\"id\":23},{\"id\":34}]");
  static final AtomicInteger RECOMMEND_STATUS = new AtomicInteger(200);
  static final AtomicReference<String> RECOMMEND_AUTH = new AtomicReference<>();
  static final AtomicInteger CONFIRM_STATUS = new AtomicInteger(200);
  static final AtomicInteger RELEASE_STATUS = new AtomicInteger(200);
  static final AtomicInteger DELAY = new AtomicInteger();
  static final AtomicReference<Consumer<Call>> OBSERVER = new AtomicReference<>();
  static final AtomicReference<Throwable> SERVER_ERROR = new AtomicReference<>();
  static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  static final java.util.concurrent.ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
  static final HttpServer SERVER = server();
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JwtTokenService tokens;
  @Autowired JdbcTemplate jdbc;
  @Autowired BookingRepository bookings;
  @Autowired ProcessedRequestRepository requests;
  @Autowired BookingTransactions transactions;
  final List<Map<String, String>> logs = new CopyOnWriteArrayList<>();
  Logger sagaLogger;
  AppenderBase<ILoggingEvent> appender;

  record Call(String path, JsonNode body, String internalKey, String traceId, String bookingId) {}

  static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(HTTP_THREADS);
      server.createContext(
          "/api/rooms/",
          exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/recommend")) {
              RECOMMEND_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
              CALLS.add(
                  new Call(
                      exchange.getRequestURI().toString(),
                      null,
                      null,
                      exchange.getRequestHeaders().getFirst("X-Trace-Id"),
                      exchange.getRequestHeaders().getFirst("X-Booking-Id")));
              byte[] response =
                  RECOMMENDATIONS.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(RECOMMEND_STATUS.get(), response.length);
              exchange.getResponseBody().write(response);
              exchange.close();
              return;
            }
            int status;
            boolean release = exchange.getRequestURI().getPath().endsWith("/release");
            try {
              var call =
                  new Call(
                      exchange.getRequestURI().getPath(),
                      new ObjectMapper().readTree(exchange.getRequestBody()),
                      exchange.getRequestHeaders().getFirst("X-Internal-Key"),
                      exchange.getRequestHeaders().getFirst("X-Trace-Id"),
                      exchange.getRequestHeaders().getFirst("X-Booking-Id"));
              CALLS.add(call);
              var observer = OBSERVER.get();
              if (observer != null) observer.accept(call);
              status = release ? RELEASE_STATUS.get() : CONFIRM_STATUS.get();
              if (!release && DELAY.get() > 0) Thread.sleep(DELAY.get());
            } catch (Throwable failure) {
              SERVER_ERROR.compareAndSet(null, failure);
              status = 500;
            }
            try {
              exchange.sendResponseHeaders(status, -1);
            } catch (IOException ignored) {
              /** Ожидается при истечении ограниченного времени ожидания клиента. */
            } finally {
              exchange.close();
            }
          });
      server.start();
      return server;
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @DynamicPropertySource
  static void discovery(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
        () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
  }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from processed_requests");
    jdbc.update("delete from bookings");
    CALLS.clear();
    RECOMMENDATIONS.set("[{\"id\":12},{\"id\":23},{\"id\":34}]");
    RECOMMEND_STATUS.set(200);
    RECOMMEND_AUTH.set(null);
    CONFIRM_STATUS.set(200);
    RELEASE_STATUS.set(200);
    DELAY.set(0);
    OBSERVER.set(null);
    SERVER_ERROR.set(null);
    sagaLogger = (Logger) LoggerFactory.getLogger(BookingSaga.class);
    appender =
        new AppenderBase<>() {
          @Override
          protected void append(ILoggingEvent event) {
            logs.add(Map.copyOf(event.getMDCPropertyMap()));
          }
        };
    appender.start();
    sagaLogger.addAppender(appender);
  }

  @AfterEach
  void cleanup() {
    try {
      assertThat(SERVER_ERROR.get()).isNull();
    } finally {
      sagaLogger.detachAppender(appender);
      appender.stop();
      jdbc.update("delete from processed_requests");
      jdbc.update("delete from bookings");
    }
  }

  @AfterAll
  static void stopServer() throws Exception {
    SERVER.stop(0);
    HTTP_THREADS.shutdownNow();
    assertThat(HTTP_THREADS.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  String body(String requestId) {
    return "{\"roomId\":47,\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-15\",\"autoSelect\":false"
        + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"")
        + "}";
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
    return post("/booking")
        .header("Authorization", "Bearer " + tokens.issueToken("user", UserRole.USER))
        .header("X-Trace-Id", "saga-trace")
        .contentType("application/json")
        .content(body);
  }

  /*
   * Исходные данные: Пользователь user бронирует номер 47 на 10–15 июня 2030 года с requestId=success и
   * traceId=saga-trace; отель отвечает 200, часы фиксированы на 1 июня.
   * Ожидаемый результат: До HTTP подтверждения в БД виден PENDING; ответ 201 и сохранённый снимок содержат
   * CONFIRMED и исходные данные; выполнен один вызов с внутренним ключом, записаны три события с
   * идентификаторами саги.
   */
  @Test
  @DisplayName("Успешное бронирование сохраняет PENDING до вызова отелей и итог CONFIRMED")
  void successCommitsPendingBeforeHttpAndStoresConfirmedBookingAndOutcome() throws Exception {
    OBSERVER.set(
        call -> {
          assertThat(call.internalKey()).isEqualTo("test-saga-key");
          assertThat(call.traceId()).isEqualTo("saga-trace");
          assertThat(call.path()).isEqualTo("/api/rooms/47/confirm-availability");
          assertThat(call.body().get("requestId").asText()).isEqualTo("success");
          assertThat(call.body().get("startDate").asText()).isEqualTo("2030-06-10");
          assertThat(call.body().get("endDate").asText()).isEqualTo("2030-06-15");
          assertThat(
                  jdbc.queryForObject(
                      "select b.status from bookings b join processed_requests p on p.booking_id = b.id where p.request_id = ?",
                      String.class,
                      "success"))
              .isEqualTo("PENDING");
        });
    var result =
        mvc.perform(request(body("success")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.roomId").value(47))
            .andExpect(jsonPath("$.startDate").value("2030-06-10"))
            .andExpect(jsonPath("$.endDate").value("2030-06-15"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.createdAt").value("2030-06-01T12:00:00Z"))
            .andExpect(jsonPath("$.requestId").value("success"))
            .andReturn();
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED));
    var stored = requests.findByRequestId("success").orElseThrow();
    assertThat(stored.getHttpStatus()).isEqualTo(201);
    assertThat(stored.getResponseSnapshot()).isEqualTo(result.getResponse().getContentAsString());
    assertThat(CALLS).hasSize(1);
    assertThat(logs)
        .hasSize(3)
        .allSatisfy(
            mdc -> {
              assertThat(mdc)
                  .containsEntry("requestId", "success")
                  .containsEntry("traceId", "saga-trace");
              assertThat(mdc).containsKeys("bookingId", "step", "outcome");
            });
  }

  /*
   * Исходные данные: Пользователь user бронирует номер 47 на 10–15 июня 2030 года без requestId; отель
   * отвечает 200.
   * Ожидаемый результат: Ответ 201 содержит корректный UUID; единственный вызов отеля и сохранённый
   * обработанный запрос используют тот же идентификатор.
   */
  @Test
  @DisplayName("Отсутствующий requestId генерируется и передаётся отелю")
  void omittedRequestIdIsGeneratedOnceAndSentToHotel() throws Exception {
    var result = mvc.perform(request(body(null))).andExpect(status().isCreated()).andReturn();
    String id =
        mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText();
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(CALLS)
        .singleElement()
        .satisfies(call -> assertThat(call.body().get("requestId").asText()).isEqualTo(id));
    assertThat(requests.findByRequestId(id)).isPresent();
  }

  /*
   * Исходные данные: Запрос номера 47 на 10–15 июня 2030 года имеет requestId=business-reject; подтверждение
   * отеля возвращает 409.
   * Ожидаемый результат: Ответ содержит ErrorDTO 409 с путём и traceId; единственное бронирование CANCELLED,
   * есть только вызов подтверждения и событие завершения CANCELLED.
   */
  @Test
  @DisplayName("Конфликт доступности отменяет бронирование без освобождения")
  void businessRejectCancelsWith409AndNeverReleases() throws Exception {
    CONFIRM_STATUS.set(409);
    mvc.perform(request(body("business-reject")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.path").value("/booking"))
        .andExpect(jsonPath("$.traceId").value("saga-trace"));
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    assertThat(CALLS)
        .hasSize(1)
        .allSatisfy(call -> assertThat(call.path()).endsWith("confirm-availability"));
    assertThat(logs)
        .anySatisfy(
            mdc ->
                assertThat(mdc)
                    .containsEntry("step", "complete")
                    .containsEntry("outcome", "CANCELLED"));
  }

  /*
   * Исходные данные: Параметр hotelStatus задаёт исходный ответ подтверждения 200, 409, 404 или 500; запрос
   * requestId=replay повторяется после смены ответа отеля на 200 и traceId.
   * Ожидаемый результат: При каждом исходном статусе повтор сохраняет точные HTTP-статус и тело первого
   * ответа, число вызовов не меняется; остаются одно бронирование и один результат.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Повтор requestId возвращает сохранённый исход без вызова отеля")
  @ValueSource(ints = {200, 409, 404, 500})
  void replayReturnsExactStoredOutcomeAndNeverCallsHotelAgain(int hotelStatus) throws Exception {
    CONFIRM_STATUS.set(hotelStatus);
    var original = mvc.perform(request(body("replay"))).andReturn().getResponse();
    int calls = CALLS.size();
    CONFIRM_STATUS.set(200);
    var replay =
        mvc.perform(request(body("replay")).header("X-Trace-Id", "new-trace"))
            .andReturn()
            .getResponse();
    assertThat(replay.getStatus()).isEqualTo(original.getStatus());
    assertThat(replay.getContentAsString()).isEqualTo(original.getContentAsString());
    assertThat(CALLS).hasSize(calls);
    assertThat(bookings.count()).isEqualTo(1);
    assertThat(requests.count()).isEqualTo(1);
  }

  /*
   * Исходные данные: Подтверждение номера 47 для requestId=unknown возвращает 404.
   * Ожидаемый результат: Клиент получает 400 с ошибкой поля roomId; бронирование CANCELLED, выполнен один
   * вызов отеля без освобождения.
   */
  @Test
  @DisplayName("Неизвестный номер отменяет бронирование с ошибкой roomId")
  void unknownRoomReturns400WithFieldErrorAndCancelsWithoutRelease() throws Exception {
    CONFIRM_STATUS.set(404);
    mvc.perform(request(body("unknown")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("roomId"));
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    assertThat(CALLS).hasSize(1);
  }

  /*
   * Исходные данные: Подтверждение номера 47 на 10–15 июня 2030 года с requestId=remote-failure возвращает
   * 500; traceId=saga-trace.
   * Ожидаемый результат: Ответ 503, бронирование CANCELLED; после подтверждения выполнен release с тем же
   * телом, в HTTP и журнале сохранены traceId, bookingId и requestId.
   */
  @Test
  @DisplayName("Ошибка отеля вызывает компенсацию с исходными идентификаторами")
  void remoteFailureCancelsAndAttemptsReleaseWithSameRequestId() throws Exception {
    CONFIRM_STATUS.set(500);
    mvc.perform(request(body("remote-failure")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value(503));
    assertThat(logs)
        .isNotEmpty()
        .allSatisfy(
            mdc ->
                assertThat(mdc)
                    .containsEntry("bookingId", bookings.findAll().get(0).getId().toString())
                    .containsEntry("requestId", "remote-failure")
                    .containsEntry("traceId", "saga-trace")
                    .containsKeys("step", "outcome"));
    assertThat(CALLS).allSatisfy(call -> assertThat(call.traceId()).isEqualTo("saga-trace"));
    assertThat(CALLS)
        .allSatisfy(
            call ->
                assertThat(call.bookingId())
                    .isEqualTo(bookings.findAll().get(0).getId().toString()));
    assertThat(CALLS).hasSize(2);
    assertThat(CALLS.get(1).path()).isEqualTo("/api/rooms/47/release");
    assertThat(CALLS.get(1).body()).isEqualTo(CALLS.get(0).body());
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
  }

  /*
   * Исходные данные: Подтверждение задерживается на 500 мс при таймауте чтения 200 мс, release возвращает
   * 503; requestId=timeout, лимит теста 10 секунд.
   * Ожидаемый результат: Возвращается 503, фиксируются два вызова и CANCELLED-бронирование; журнал содержит
   * шаг release с исходом FAILED.
   */
  @Test
  @DisplayName("Таймаут и неудачная компенсация завершаются отменой в ограниченное время")
  @Timeout(10)
  void timeoutAndFailedCompensationAreBoundedAndReturn503() throws Exception {
    DELAY.set(500);
    RELEASE_STATUS.set(503);
    mvc.perform(request(body("timeout"))).andExpect(status().isServiceUnavailable());
    assertThat(CALLS).hasSize(2);
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    assertThat(logs)
        .anySatisfy(
            mdc ->
                assertThat(mdc)
                    .containsEntry("step", "release")
                    .containsEntry("outcome", "FAILED"));
  }

  /*
   * Исходные данные: Параметр autoSelect включает подбор или номер 47; наблюдатель создаёт слот до ответа,
   * задержанного на 500 мс при таймауте 200 мс; requestId=lost-response.
   * Ожидаемый результат: В обоих режимах слот освобождён тем же телом, бронирование CANCELLED, сохранён ответ
   * 503; выполнены три вызова при подборе или два без него, повтор не добавляет вызовов.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Потерянный ответ подтверждения компенсируется и повторяет сохранённый отказ")
  @ValueSource(booleans = {true, false})
  @Timeout(10)
  void lostConfirmResponseReleasesCreatedSlotAndReplays503(boolean autoSelect) throws Exception {
    var heldSlot = new AtomicReference<JsonNode>();
    OBSERVER.set(
        call -> {
          if (call.path().endsWith("/confirm-availability")) {
            assertThat(heldSlot.compareAndSet(null, call.body())).isTrue();
          } else {
            assertThat(call.path()).endsWith("/release");
            assertThat(heldSlot.getAndSet(null)).isEqualTo(call.body());
          }
        });
    DELAY.set(500);
    var payload = body("lost-response").replace("false", Boolean.toString(autoSelect));
    var original =
        mvc.perform(request(payload))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(heldSlot.get()).isNull();
    assertThat(CALLS).hasSize(autoSelect ? 3 : 2);
    assertThat(CALLS.get(CALLS.size() - 1).path()).endsWith("/release");
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(booking -> assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    assertThat(requests.findByRequestId("lost-response"))
        .hasValueSatisfying(
            saved -> {
              assertThat(saved.getHttpStatus()).isEqualTo(503);
              assertThat(saved.getResponseSnapshot()).isEqualTo(original);
            });
    int calls = CALLS.size();
    mvc.perform(request(payload))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(original));
    assertThat(CALLS).hasSize(calls);
  }

  /*
   * Исходные данные: Параметры start и end задают равные даты, обратный период или начало в прошлом
   * относительно 1 июня 2030 года; field указывает ожидаемое ошибочное поле.
   * Ожидаемый результат: Каждый набор возвращает 400 с ошибкой поля field; бронирования, обработанные запросы
   * и вызовы отеля отсутствуют.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Недопустимые даты отклоняются до записи бронирования и HTTP-вызовов")
  @CsvSource({
    "2030-06-10,2030-06-10,endDate",
    "2030-06-15,2030-06-10,endDate",
    "2030-05-31,2030-06-10,startDate"
  })
  void invalidDatesReturn400WithoutBookingOrHttp(String start, String end, String field)
      throws Exception {
    mvc.perform(
            request(
                mapper.writeValueAsString(
                    Map.of("roomId", 47, "startDate", start, "endDate", end))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    assertThat(bookings.count()).isZero();
    assertThat(requests.count()).isZero();
    assertThat(CALLS).isEmpty();
  }

  /*
   * Исходные данные: Параметр payload задаёт пустой объект, roomId=0 либо requestId из пробела с допустимыми
   * датами.
   * Ожидаемый результат: Каждый вариант даёт 400 с массивом fieldErrors; бронирования и вызовы отеля не
   * создаются.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Отсутствующие и неверные поля бронирования отклоняются")
  @ValueSource(
      strings = {
        "{}",
        "{\"roomId\":0,\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-15\"}",
        "{\"roomId\":47,\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-15\",\"requestId\":\" \"}"
      })
  void missingOrInvalidFieldsReturn400(String payload) throws Exception {
    mvc.perform(request(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors").isArray());
    assertThat(bookings.count()).isZero();
    assertThat(CALLS).isEmpty();
  }

  /*
   * Исходные данные: Параметр roomField задаёт отсутствие roomId, -99 или 47; autoSelect=true,
   * requestId=auto; рекомендации 12, 23, 34, номер 12 возвращает 409, номер 23 — 200.
   * Ожидаемый результат: Для каждого варианта выбран номер 23 и статус CONFIRMED; рекомендации получают
   * исходную авторизацию, кандидаты записываются до подтверждения и вызываются по порядку; повтор возвращает
   * тот же ответ без новых вызовов.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Автоподбор соблюдает порядок рекомендаций и игнорирует явный номер")
  @ValueSource(strings = {"", ",\"roomId\":-99", ",\"roomId\":47"})
  void autoSelectionUsesRecommendationOrderAndIgnoresRoomId(String roomField) throws Exception {
    OBSERVER.set(
        call -> {
          CONFIRM_STATUS.set(call.path().contains("/12/") ? 409 : 200);
          assertThat(call.body().get("requestId").asText()).isEqualTo("auto");
          assertThat(jdbc.queryForObject("select room_id from bookings", Long.class))
              .isEqualTo(call.path().contains("/12/") ? 12L : 23L);
        });
    String payload =
        "{\"autoSelect\":true,\"requestId\":\"auto\",\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-15\""
            + roomField
            + "}";
    var incoming = request(payload);
    String authorization =
        incoming
            .buildRequest(new org.springframework.mock.web.MockServletContext())
            .getHeader("Authorization");
    var original =
        mvc.perform(incoming)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.roomId").value(23))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(RECOMMEND_AUTH.get()).isEqualTo(authorization);
    assertThat(CALLS)
        .extracting(Call::path)
        .containsExactly(
            "/api/rooms/recommend?startDate=2030-06-10&endDate=2030-06-15",
            "/api/rooms/12/confirm-availability",
            "/api/rooms/23/confirm-availability");
    mvc.perform(request(payload))
        .andExpect(status().isCreated())
        .andExpect(content().string(original));
    assertThat(CALLS).hasSize(3);
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(
            b -> {
              assertThat(b.getRoomId()).isEqualTo(23L);
              assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            });
  }

  /*
   * Исходные данные: Для requestId=first-candidate включён автоподбор; рекомендации содержат 12, 23 и 34,
   * подтверждение отвечает 200.
   * Ожидаемый результат: Ответ 201 содержит номер 12; выполнены только запрос рекомендаций и подтверждение
   * номера 12.
   */
  @Test
  @DisplayName("Первый успешно подтверждённый кандидат завершает автоподбор")
  void firstSuccessfulCandidateStopsSelection() throws Exception {
    mvc.perform(request(body("first-candidate").replace("false", "true")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.roomId").value(12));
    assertThat(CALLS)
        .extracting(Call::path)
        .containsExactly(
            "/api/rooms/recommend?startDate=2030-06-10&endDate=2030-06-15",
            "/api/rooms/12/confirm-availability");
  }

  /*
   * Исходные данные: Параметр recommendations задаёт пустой список либо номера 12 и 23; все подтверждения
   * отвечают 409, requestId=exhausted.
   * Ожидаемый результат: Каждый вариант даёт 409 и CANCELLED; число вызовов равно одному запросу рекомендаций
   * плюс числу кандидатов, повтор возвращает исходное тело без новых вызовов.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Пустые и исчерпанные рекомендации отменяют автоподбор с конфликтом")
  @ValueSource(strings = {"[]", "[{\"id\":12},{\"id\":23}]"})
  void exhaustedRecommendationsCancelWith409(String recommendations) throws Exception {
    RECOMMENDATIONS.set(recommendations);
    CONFIRM_STATUS.set(409);
    var payload = body("exhausted").replace("false", "true");
    var original =
        mvc.perform(request(payload))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int calls = CALLS.size();
    assertThat(calls).isEqualTo(1 + mapper.readTree(recommendations).size());
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    mvc.perform(request(payload))
        .andExpect(status().isConflict())
        .andExpect(content().string(original));
    assertThat(CALLS).hasSize(calls);
  }

  /*
   * Исходные данные: Для requestId=recommend-failure включён автоподбор; запрос рекомендаций возвращает 503.
   * Ожидаемый результат: Ответ 503, бронирование CANCELLED, выполнен только один запрос рекомендаций.
   */
  @Test
  @DisplayName("Ошибка рекомендаций отменяет бронирование без подтверждения и компенсации")
  void recommendationFailureCancelsWithoutConfirmOrRelease() throws Exception {
    RECOMMEND_STATUS.set(503);
    mvc.perform(request(body("recommend-failure").replace("false", "true")))
        .andExpect(status().isServiceUnavailable());
    assertThat(CALLS).hasSize(1);
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
  }

  /*
   * Исходные данные: Для requestId=auto-failure включён подбор из номеров 12, 23 и 34; подтверждение
   * возвращает 500.
   * Ожидаемый результат: Ответ 503, бронирование CANCELLED; после рекомендаций вызваны только подтверждение и
   * release номера 12 с одинаковыми телами.
   */
  @Test
  @DisplayName("Ошибка подтверждения останавливает автоподбор и освобождает текущий номер")
  void autoSelectionStopsOnRemoteFailureAndCompensatesCurrentCandidate() throws Exception {
    CONFIRM_STATUS.set(500);
    mvc.perform(request(body("auto-failure").replace("false", "true")))
        .andExpect(status().isServiceUnavailable());
    assertThat(CALLS)
        .extracting(Call::path)
        .containsExactly(
            "/api/rooms/recommend?startDate=2030-06-10&endDate=2030-06-15",
            "/api/rooms/12/confirm-availability",
            "/api/rooms/12/release");
    assertThat(CALLS.get(2).body()).isEqualTo(CALLS.get(1).body());
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
  }

  /*
   * Исходные данные: Корректные запросы номера 47 отправляются без авторизации и с токеном роли ADMIN.
   * Ожидаемый результат: Ответы имеют статусы 401 и 403 соответственно, включая поле status в теле; отель не
   * вызывается.
   */
  @Test
  @DisplayName("Анонимный клиент и администратор не могут создавать бронирования")
  void anonymousAndAdminCannotCreateBookings() throws Exception {
    mvc.perform(post("/booking").contentType("application/json").content(body("anonymous")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    mvc.perform(
            post("/booking")
                .header("Authorization", "Bearer " + tokens.issueToken("admin", UserRole.ADMIN))
                .contentType("application/json")
                .content(body("admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
    assertThat(CALLS).isEmpty();
  }

  /*
   * Исходные данные: Пользователь user успешно бронирует с requestId=owner; затем тот же запрос отправляет
   * имя admin с ролью USER.
   * Ожидаемый результат: Первый ответ 201, чужой повтор даёт 409; число вызовов отеля остаётся равным одному.
   */
  @Test
  @DisplayName("Другой пользователь не получает сохранённый исход по чужому requestId")
  void anotherUserCannotReadAStoredOutcomeThroughRequestIdReuse() throws Exception {
    mvc.perform(request(body("owner"))).andExpect(status().isCreated());
    mvc.perform(
            post("/booking")
                .header("Authorization", "Bearer " + tokens.issueToken("admin", UserRole.USER))
                .contentType("application/json")
                .content(body("owner")))
        .andExpect(status().isConflict());
    assertThat(CALLS).hasSize(1);
  }

  /*
   * Исходные данные: После успешного запроса requestId=conditional вызывается finish с CANCELLED, HTTP 409 и
   * пустым JSON.
   * Ожидаемый результат: Возвращается исходный сохранённый результат 201 с прежним телом, а единственное
   * бронирование остаётся CONFIRMED.
   */
  @Test
  @DisplayName("Запоздалое завершение не перезаписывает подтверждённое бронирование")
  void terminalStateCannotBeOverwrittenByStaleCompletion() throws Exception {
    var result =
        mvc.perform(request(body("conditional"))).andExpect(status().isCreated()).andReturn();
    var stale = transactions.finish("conditional", BookingStatus.CANCELLED, 409, "{}");
    assertThat(stale.outcome().status()).isEqualTo(201);
    assertThat(stale.outcome().body()).isEqualTo(result.getResponse().getContentAsString());
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED));
  }

  /*
   * Исходные данные: Четыре потока одновременно отправляют requestId=concurrent для номера 47; подтверждение
   * задержано на 100 мс, лимит теста 15 секунд.
   * Ожидаемый результат: Все получают 201 с одинаковыми телами; сохранены одно бронирование и один результат,
   * выполнен ровно один вызов отеля, потоки завершаются в срок.
   */
  @Test
  @DisplayName("Четыре конкурентных повтора выполняют сагу ровно один раз")
  @Timeout(15)
  void concurrentDuplicateRequestsReturnOneStoredOutcomeWithoutRepeatingSaga() throws Exception {
    DELAY.set(100);
    var threads = Executors.newFixedThreadPool(4);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            threads.submit(
                () -> {
                  start.await();
                  return mvc.perform(request(body("concurrent")))
                      .andExpect(status().isCreated())
                      .andReturn()
                      .getResponse()
                      .getContentAsString();
                }));
      start.countDown();
      var original = futures.get(0).get(8, TimeUnit.SECONDS);
      for (var future : futures) assertThat(future.get(8, TimeUnit.SECONDS)).isEqualTo(original);
      assertThat(bookings.count()).isEqualTo(1);
      assertThat(requests.count()).isEqualTo(1);
      assertThat(CALLS).hasSize(1);
    } finally {
      start.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancel(
      Long id, String user) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
            "/booking/{id}", id)
        .header("Authorization", "Bearer " + tokens.issueToken(user, UserRole.USER));
  }

  /*
   * Исходные данные: Создано подтверждённое бронирование requestId=cancel; параметр releaseStatus задаёт 200
   * либо 500, владелец выполняет две отмены.
   * Ожидаемый результат: Обе отмены дают 204; release с исходным телом вызывается один раз при ещё CONFIRMED
   * в БД, итог CANCELLED; при 500 журнал содержит ERROR с bookingId и requestId.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Отмена освобождает слот до смены статуса и остаётся идемпотентной при ошибке")
  @ValueSource(ints = {200, 500})
  void cancellationReleasesBeforeStatusChangeAndIsIdempotentEvenOnHotelFailure(int releaseStatus)
      throws Exception {
    mvc.perform(request(body("cancel"))).andExpect(status().isCreated());
    Long id = bookings.findAll().get(0).getId();
    RELEASE_STATUS.set(releaseStatus);
    OBSERVER.set(
        call -> {
          assertThat(call.path()).endsWith("/release");
          assertThat(call.body().get("requestId").asText()).isEqualTo("cancel");
          assertThat(call.body()).isEqualTo(CALLS.get(0).body());
          assertThat(
                  jdbc.queryForObject("select status from bookings where id = ?", String.class, id))
              .isEqualTo("CONFIRMED");
        });
    var logger =
        (Logger)
            LoggerFactory.getLogger(com.example.booking.service.BookingCancellationService.class);
    var errors = new CopyOnWriteArrayList<ILoggingEvent>();
    var capture =
        new AppenderBase<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent event) {
            errors.add(event);
          }
        };
    capture.start();
    logger.addAppender(capture);
    try {
      mvc.perform(cancel(id, "user")).andExpect(status().isNoContent());
      mvc.perform(cancel(id, "user")).andExpect(status().isNoContent());
      assertThat(bookings.findById(id).orElseThrow().getStatus())
          .isEqualTo(BookingStatus.CANCELLED);
      assertThat(CALLS).hasSize(2);
      if (releaseStatus == 500)
        assertThat(errors)
            .anySatisfy(
                event -> {
                  assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                  assertThat(event.getFormattedMessage())
                      .contains("bookingId=" + id, "requestId=cancel");
                });
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  /*
   * Исходные данные: Есть подтверждённое бронирование requestId=cancel-owner; отмена запрашивается другим
   * владельцем, для отсутствующего id, анонимно и с ролью ADMIN.
   * Ожидаемый результат: Ответы соответственно 404, 404, 401 и 403; остаётся единственный исходный вызов
   * подтверждения, статус бронирования CONFIRMED.
   */
  @Test
  @DisplayName("Отмена проверяет владельца и роль до обращения к отелю")
  void cancellationEnforcesOwnershipAndAuthentication() throws Exception {
    mvc.perform(request(body("cancel-owner"))).andExpect(status().isCreated());
    Long id = bookings.findAll().get(0).getId();
    mvc.perform(cancel(id, "other")).andExpect(status().isNotFound());
    mvc.perform(cancel(Long.MAX_VALUE, "user")).andExpect(status().isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/booking/{id}", id))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/booking/{id}", id)
                .header("Authorization", "Bearer " + tokens.issueToken("admin", UserRole.ADMIN)))
        .andExpect(status().isForbidden());
    assertThat(CALLS).hasSize(1);
    assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
  }

  /*
   * Исходные данные: Наблюдатель подтверждения requestId=cancel-pending дважды отменяет ещё незавершённое
   * бронирование от имени владельца.
   * Ожидаемый результат: Обе отмены дают 204, создание и его повтор — 409; итог CANCELLED, вызваны только
   * подтверждение и одно освобождение.
   */
  @Test
  @DisplayName("Отмена PENDING компенсирует позднее подтверждение один раз")
  void pendingCancellationCompensatesLateConfirmationOnce() throws Exception {
    OBSERVER.set(
        call -> {
          if (call.path().endsWith("/confirm-availability")) {
            Long id =
                jdbc.queryForObject(
                    "select booking_id from processed_requests where request_id = ?",
                    Long.class,
                    "cancel-pending");
            try {
              mvc.perform(cancel(id, "user")).andExpect(status().isNoContent());
              mvc.perform(cancel(id, "user")).andExpect(status().isNoContent());
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          }
        });
    mvc.perform(request(body("cancel-pending"))).andExpect(status().isConflict());
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED));
    assertThat(CALLS)
        .extracting(Call::path)
        .containsExactly("/api/rooms/47/confirm-availability", "/api/rooms/47/release");
    mvc.perform(request(body("cancel-pending"))).andExpect(status().isConflict());
    assertThat(CALLS).hasSize(2);
  }
}
