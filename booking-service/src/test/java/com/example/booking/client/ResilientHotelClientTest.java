package com.example.booking.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.booking.config.HotelResilienceProperties;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.entity.BookingStatus;
import com.example.booking.saga.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Timeout(10)
class ResilientHotelClientTest {
  HttpServer server;
  ExecutorService httpThreads;
  ResilientHotelClient client;
  final AtomicInteger outboundAttempts = new AtomicInteger();
  final AtomicInteger status = new AtomicInteger(200);
  final AtomicInteger delay = new AtomicInteger();
  final List<String> paths = new CopyOnWriteArrayList<>();
  final List<String> bodies = new CopyOnWriteArrayList<>();
  final List<String> traces = new CopyOnWriteArrayList<>();
  final List<Long> times = new CopyOnWriteArrayList<>();
  final LocalDate start = LocalDate.of(2030, 6, 10);
  final LocalDate end = start.plusDays(2);

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpThreads = Executors.newCachedThreadPool();
    server.setExecutor(httpThreads);
    server.createContext(
        "/api/rooms",
        exchange -> {
          boolean release = exchange.getRequestURI().getPath().endsWith("/release");
          paths.add(exchange.getRequestURI().getPath());
          bodies.add(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          traces.add(Objects.toString(exchange.getRequestHeaders().getFirst("X-Trace-Id"), ""));
          times.add(System.nanoTime());
          try {
            if (!release && delay.get() > 0) Thread.sleep(delay.get());
            int code = release ? 200 : status.get();
            if (exchange.getRequestURI().getPath().endsWith("/recommend") && code == 200) {
              byte[] body = "[{\"id\":47}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(code, body.length);
              exchange.getResponseBody().write(body);
            } else exchange.sendResponseHeaders(code, -1);
          } catch (java.io.IOException ignored) {
            // Клиент намеренно закрывает запросы с истёкшим сроком ожидания.
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    client = createClient(3, Duration.ofSeconds(1));
  }

  ResilientHotelClient createClient(int attempts, Duration timeout) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(100));
    factory.setReadTimeout(Duration.ofMillis(200));
    var builder = RestClient.builder().requestFactory(factory);
    // RestHotelClient использует логическое имя обнаружения; разрешаем его напрямую для теста с
    // настоящим HTTP.
    builder.requestInterceptor(
        (request, body, execution) -> {
          outboundAttempts.incrementAndGet();
          var resolved =
              new org.springframework.http.client.support.HttpRequestWrapper(request) {
                @Override
                public java.net.URI getURI() {
                  return java.net.URI.create(
                      "http://127.0.0.1:"
                          + server.getAddress().getPort()
                          + request.getURI().getRawPath()
                          + (request.getURI().getRawQuery() == null
                              ? ""
                              : "?" + request.getURI().getRawQuery()));
                }
              };
          return execution.execute(resolved, body);
        });
    return new ResilientHotelClient(
        new RestHotelClient(builder, "test-key"),
        new HotelResilienceProperties(
            timeout, attempts, Duration.ofMillis(10), 2, 50, 10, 10, Duration.ofSeconds(10), 3, 4));
  }

  @AfterEach
  void cleanup() throws Exception {
    MDC.clear();
    client.close();
    server.stop(0);
    httpThreads.shutdownNow();
    assertThat(httpThreads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
  }

  void confirm() {
    client.confirm(47L, start, end, "same-id");
  }

  /*
   * Исходные данные: Параметр code принимает 400, 401, 403, 404, 409, 422 и 429; HTTP-заглушка возвращает
   * его на 12 подтверждений номера 47 за 10–12 июня 2030 года.
   * Ожидаемый результат: Каждый вызов выбрасывает HotelCallException с выбранным code, всего 12
   * HTTP-запросов; после ответа 200 подтверждение проходит.
   */
  @DisplayName("Бизнес-ошибки не повторяются и не размыкают автоматический выключатель")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(ints = {400, 401, 403, 404, 409, 422, 429})
  void businessRejectsAreNeverRetriedOrCountedAsBreakerFailures(int code) {
    status.set(code);
    for (int i = 0; i < 12; i++)
      assertThatThrownBy(this::confirm)
          .isInstanceOfSatisfying(
              HotelCallException.class, ex -> assertThat(ex.getStatus()).isEqualTo(code));
    assertThat(paths).hasSize(12);
    status.set(200);
    confirm(); // Автоматический выключатель продолжает пропускать вызовы после более десяти
    // бизнес-отказов.
  }

  /*
   * Исходные данные: Подтверждение номера 47 на 10–12 июня 2030 года с requestId same-id получает 503; в
   * MDC установлен resilience-trace, разрешены три попытки.
   * Ожидаемый результат: После трёх одинаковых запросов с тем же traceId возникает HotelCallException;
   * интервалы не меньше 10 и 20 мс, освобождение использует то же тело.
   */
  @DisplayName("Повторы при ошибке сервера сохраняют тело и traceId с растущей задержкой")
  @Test
  void serverErrorsRetryWithExponentialBackoffAndPreserveIdentityAndTrace() {
    MDC.put("traceId", "resilience-trace");
    status.set(503);
    assertThatThrownBy(this::confirm).isInstanceOf(HotelCallException.class);
    assertThat(paths).hasSize(3);
    assertThat(new HashSet<>(bodies)).hasSize(1);
    assertThat(bodies.get(0)).contains("same-id");
    assertThat(traces).containsOnly("resilience-trace");
    assertThat(Duration.ofNanos(times.get(1) - times.get(0)))
        .isGreaterThanOrEqualTo(Duration.ofMillis(10));
    assertThat(Duration.ofNanos(times.get(2) - times.get(1)))
        .isGreaterThanOrEqualTo(Duration.ofMillis(20));
    client.release(47L, start, end, "same-id");
    assertThat(paths.get(3)).endsWith("/release");
    assertThat(bodies.get(3)).isEqualTo(bodies.get(0));
  }

  /*
   * Исходные данные: Заглушка подтверждения номера 47 с requestId same-id сначала выбрасывает ошибку 502,
   * затем завершается успешно.
   * Ожидаемый результат: Подтверждение завершается без исключения после ровно двух вызовов делегата.
   */
  @DisplayName("Повтор подтверждения завершается после восстановления отеля")
  @Test
  void retriesRecoverOnNextAttempt() {
    var delegate = mock(RestHotelClient.class);
    doThrow(new HotelCallException(502, null))
        .doNothing()
        .when(delegate)
        .confirm(47L, start, end, "same-id");
    try (var resilient = newClientForMock(delegate)) {
      resilient.confirm(47L, start, end, "same-id");
      verify(delegate, times(2)).confirm(47L, start, end, "same-id");
    }
  }

  ResilientHotelClient newClientForMock(RestHotelClient delegate) {
    return new ResilientHotelClient(
        delegate,
        new HotelResilienceProperties(
            Duration.ofSeconds(1),
            3,
            Duration.ofMillis(1),
            2,
            50,
            10,
            10,
            Duration.ofSeconds(10),
            3,
            4));
  }

  /*
   * Исходные данные: Три попытки ограничены 50 мс каждая, HTTP-заглушка задерживает подтверждение на 500
   * мс; сага бронирует номер 47 с requestId timeout.
   * Ожидаемый результат: Сага возвращает 503 и сохраняет отмену; три подтверждения и одно освобождение
   * передают одинаковое тело.
   */
  @DisplayName("Исчерпание тайм-аутов отменяет бронь и освобождает номер")
  @Test
  void timeoutExhaustionCancelsBookingAndAttemptsIdempotentRelease() {
    client.close();
    client = createClient(3, Duration.ofMillis(50));
    delay.set(500);
    assertCancelledBySaga("timeout");
    assertThat(paths).filteredOn(path -> path.endsWith("confirm-availability")).hasSize(3);
    assertThat(paths).filteredOn(path -> path.endsWith("/release")).hasSize(1);
    assertThat(new HashSet<>(bodies)).hasSize(1);
  }

  /*
   * Исходные данные: HTTP-сервер остановлен; сага бронирует номер 47 на 10–12 июня 2030 года с requestId
   * down.
   * Ожидаемый результат: Сага возвращает 503 и сохраняет отмену; выполняются шесть исходящих попыток: три
   * подтверждения и три освобождения.
   */
  @DisplayName("Недоступность отеля отменяет бронь даже при неудачной компенсации")
  @Test
  void hotelDownExhaustsRetriesAndCancelsEvenWhenReleaseFails() {
    server.stop(0);
    assertCancelledBySaga("down");
    assertThat(outboundAttempts.get())
        .isEqualTo(6); // Три подтверждения, затем три попытки освобождения без гарантии успеха.
  }

  /*
   * Исходные данные: Без повторов выполняются пять успешных подтверждений и пять ответов 500; затем
   * запускается сага с requestId open-breaker.
   * Ожидаемый результат: После десяти HTTP-запросов сага возвращает 503 и сохраняет отмену; единственный
   * дополнительный HTTP-запрос освобождает номер.
   */
  @DisplayName("Пять ошибок из десяти вызовов блокируют подтверждение, но допускают компенсацию")
  @Test
  void breakerOpensAtFiftyPercentOfTenCallsAndStillAllowsCompensation() {
    client.close();
    client = createClient(1, Duration.ofSeconds(1));
    for (int i = 0; i < 5; i++) confirm();
    status.set(500);
    for (int i = 0; i < 5; i++)
      assertThatThrownBy(this::confirm).isInstanceOf(HotelCallException.class);
    assertThat(paths).hasSize(10);
    assertCancelledBySaga("open-breaker");
    assertThat(paths).hasSize(11);
    assertThat(paths.get(10)).endsWith("/release");
  }

  /*
   * Исходные данные: Без повторов и с тайм-аутом 50 мс выполняются десять подтверждений номера 47; сервер
   * задерживает каждое на 500 мс.
   * Ожидаемый результат: Все подтверждения завершаются HotelCallException; следующий вызов не увеличивает
   * число исходящих попыток, освобождение добавляет ровно одну попытку и достигает отеля.
   */
  @DisplayName("Повторяющиеся тайм-ауты блокируют подтверждение, но не освобождение")
  @Test
  void repeatedTimeoutsOpenBreakerAndCompensationStillReachesHotel() {
    client.close();
    client = createClient(1, Duration.ofMillis(50));
    delay.set(500);
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(this::confirm).isInstanceOf(HotelCallException.class);
    }
    int attemptsBeforeOpenCall = outboundAttempts.get();
    assertThatThrownBy(this::confirm).isInstanceOf(HotelCallException.class);
    assertThat(outboundAttempts.get()).isEqualTo(attemptsBeforeOpenCall);

    client.release(47L, start, end, "same-id");
    assertThat(outboundAttempts.get()).isEqualTo(attemptsBeforeOpenCall + 1);
    assertThat(paths).anyMatch(path -> path.endsWith("/release"));
  }

  /*
   * Исходные данные: Делегат подтверждения номера 47 с requestId same-id выбрасывает HotelCallException с
   * кодом 0 и причиной IllegalArgumentException.
   * Ожидаемый результат: Клиент передаёт HotelCallException вызывающему коду и обращается к делегату ровно
   * один раз.
   */
  @DisplayName("Ошибка преобразования ответа клиента не повторяется")
  @Test
  void clientSideConversionFailuresAreNotRetried() {
    var delegate = mock(RestHotelClient.class);
    doThrow(new HotelCallException(0, new IllegalArgumentException("Invalid response")))
        .when(delegate)
        .confirm(47L, start, end, "same-id");
    try (var resilient = newClientForMock(delegate)) {
      assertThatThrownBy(() -> resilient.confirm(47L, start, end, "same-id"))
          .isInstanceOf(HotelCallException.class);
      verify(delegate).confirm(47L, start, end, "same-id");
    }
  }

  /*
   * Исходные данные: HTTP-заглушка рекомендаций на 10–12 июня 2030 года возвращает 500; отдельный делегат
   * освобождения номера 47 выбрасывает ошибку 503.
   * Ожидаемый результат: Обе операции завершаются HotelCallException после трёх попыток; запросы
   * рекомендаций идут только к /recommend, делегат освобождения вызывается три раза.
   */
  @DisplayName("Рекомендации и освобождение повторяются при серверных ошибках")
  @Test
  void recommendationsAndReleaseAreAlsoRetried() {
    status.set(500);
    assertThatThrownBy(() -> client.recommend(start, end, "Bearer token"))
        .isInstanceOf(HotelCallException.class);
    assertThat(paths).hasSize(3).allMatch(path -> path.endsWith("/recommend"));
    var delegate = mock(RestHotelClient.class);
    doThrow(new HotelCallException(503, null)).when(delegate).release(47L, start, end, "same-id");
    try (var resilient = newClientForMock(delegate)) {
      assertThatThrownBy(() -> resilient.release(47L, start, end, "same-id"))
          .isInstanceOf(HotelCallException.class);
      verify(delegate, times(3)).release(47L, start, end, "same-id");
    }
  }

  void assertCancelledBySaga(String requestId) {
    var transactions = mock(BookingTransactions.class);
    var request = new CreateBookingRequest(47L, start, end, false, requestId);
    var work = new BookingWork(1L, "user", 47L, start, end, requestId);
    when(transactions.find(requestId)).thenReturn(Optional.empty());
    when(transactions.start("user", request, requestId)).thenReturn(work);
    when(transactions.finish(eq(requestId), eq(BookingStatus.CANCELLED), eq(503), anyString()))
        .thenAnswer(
            invocation ->
                new BookingRecord(work, new BookingOutcome(503, invocation.getArgument(3))));
    var saga =
        new BookingSaga(
            transactions,
            client,
            Clock.fixed(Instant.parse("2030-06-01T00:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper().findAndRegisterModules(),
            Duration.ofSeconds(1),
            Duration.ofMillis(10));
    var result = saga.create("user", request, "Bearer token");
    assertThat(result.status()).isEqualTo(503);
    assertThat(result.body()).contains("Hotel Service is unavailable", "\"status\":503");
    verify(transactions).finish(eq(requestId), eq(BookingStatus.CANCELLED), eq(503), anyString());
  }
}
