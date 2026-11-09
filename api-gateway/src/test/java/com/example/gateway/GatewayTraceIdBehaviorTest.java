package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.gateway.filter.TraceIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Проверяет traceId на шлюзе: отсутствующий или пустой X-Trace-Id создаётся один раз и передаётся
 * заглушке; значение клиента сохраняется, из нескольких значений выбирается первое. Даже
 * отклонённый запрос получает traceId в журнале шлюза. LoggingEvent читает MDC лениво из
 * вызывающего потока, поэтому после запроса ListAppender увидел бы уже очищенную карту. Здесь
 * getMDCPropertyMap() копируется синхронно в doAppend, в том же потоке и до MDC.remove.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "eureka.client.enabled=false")
@ContextConfiguration(initializers = GatewayTraceIdBehaviorTest.StubRouteUrisInitializer.class)
class GatewayTraceIdBehaviorTest {

  private static final List<RecordedRequest> HOTEL_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<RecordedRequest> BOOKING_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<Map<String, String>> CAPTURED_MDC_SNAPSHOTS =
      new CopyOnWriteArrayList<>();

  private static HttpServer hotelStub;
  private static HttpServer bookingStub;

  private static MdcSnapshotAppender mdcAppender;
  private static Logger traceIdFilterLogger;

  @Autowired private WebTestClient client;

  @Autowired private TraceIdFilter traceIdFilter;

  @LocalServerPort private int port;

  @BeforeEach
  void attachAppenderAndResetCaptures() {
    HOTEL_REQUESTS.clear();
    BOOKING_REQUESTS.clear();
    CAPTURED_MDC_SNAPSHOTS.clear();

    // Перенастройка журналирования Boot при загрузке контекста удаляет ранее подключённые
    // обработчики,
    // поэтому подключаем их после внедрения; предварительное отключение обеспечивает
    // идемпотентность между тестами.
    if (mdcAppender == null) {
      mdcAppender = new MdcSnapshotAppender();
      mdcAppender.start();
    }
    traceIdFilterLogger = (Logger) LoggerFactory.getLogger(TraceIdFilter.class);
    traceIdFilterLogger.detachAppender(mdcAppender);
    traceIdFilterLogger.addAppender(mdcAppender);
  }

  @BeforeAll
  static void startStubs() throws IOException {
    hotelStub = startStub(HOTEL_REQUESTS);
    bookingStub = startStub(BOOKING_REQUESTS);
  }

  @AfterAll
  static void stopStubsAndAppender() {
    if (traceIdFilterLogger != null && mdcAppender != null) {
      traceIdFilterLogger.detachAppender(mdcAppender);
      mdcAppender.stop();
    }
    if (hotelStub != null) {
      hotelStub.stop(0);
    }
    if (bookingStub != null) {
      bookingStub.stop(0);
    }
  }

  /**
   * Перенаправление аналогично GatewayRoutingBehaviorTest; этот класс использует собственные
   * заглушки.
   */
  static class StubRouteUrisInitializer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      context.addApplicationListener(
          event -> {
            if (event instanceof ContextRefreshedEvent) {
              GatewayProperties properties = context.getBean(GatewayProperties.class);
              for (RouteDefinition route : properties.getRoutes()) {
                URI stub =
                    "bookings".equals(route.getId()) ? stubUri(bookingStub) : stubUri(hotelStub);
                route.setUri(stub);
              }
            }
          });
    }
  }

  // ---------------------------------------------------------------------
  // Сценарии: заголовок отсутствует, задан клиентом, пустой, многозначный,
  // либо запрос отклонён; проверяются маршруты отелей и бронирований.
  // ---------------------------------------------------------------------

  /*
   * Исходные данные: Записи заглушек и журнала очищены; выполняется GET /api/hotels/x?y=1 без X-Trace-Id.
   * Ожидаемый результат: Ответ 200; единственный вызов отелей содержит непустой traceId, совпадающий с MDC
   * журнала; вызовов бронирований нет.
   */
  @Test
  @DisplayName("Шлюз создаёт и журналирует идентификатор трассировки для запроса к отелям")
  void missingHeaderIsGeneratedForwardedAndLoggedOnHotelsFamily() {
    client.get().uri("/api/hotels/x?y=1").exchange().expectStatus().isOk();

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              // Значение создаётся, передаётся далее и совпадает со значением в журнале шлюза.
              assertThat(recorded.traceId()).isNotBlank();
              assertThat(singleMdcSnapshot())
                  .containsEntry(TraceIdFilter.MDC_KEY, recorded.traceId());
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется GET /api/hotels с X-Trace-Id: client-abc; записи заглушек и журнала очищены.
   * Ожидаемый результат: Ответ 200; единственный вызов отелей и MDC содержат client-abc; вызовов бронирований
   * нет.
   */
  @Test
  @DisplayName("Идентификатор трассировки клиента сохраняется в запросе к отелям и журнале")
  void clientSuppliedHeaderIsPropagatedVerbatimOnHotelsFamily() {
    client
        .get()
        .uri("/api/hotels")
        .header(TraceIdFilter.TRACE_HEADER, "client-abc")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.traceId()).isEqualTo("client-abc");
              assertThat(singleMdcSnapshot()).containsEntry(TraceIdFilter.MDC_KEY, "client-abc");
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Через сокет отправляется GET /api/hotels с X-Trace-Id из трёх пробелов; записи очищены.
   * Ожидаемый результат: Строка ответа начинается с HTTP/1.1 200; заглушка отелей получает непустой traceId,
   * совпадающий с MDC; вызовов бронирований нет.
   */
  @Test
  @DisplayName("Пустой заголовок трассировки заменяется сгенерированным идентификатором")
  void blankHeaderIsTreatedAsAbsentAndGenerated() throws Exception {
    // Тестовый клиент Netty отказывается отправлять заголовок только из пробелов
    // из-за недопустимого символа 0x20, поэтому пустое значение отправляется
    // через обычный сокет, как у клиента с некорректным поведением.
    String statusLine =
        sendRaw(
            "GET /api/hotels HTTP/1.1\r\n"
                + "Host: localhost:"
                + port
                + "\r\n"
                + TraceIdFilter.TRACE_HEADER
                + ":   \r\n"
                + "Connection: close\r\n\r\n");
    assertThat(statusLine).startsWith("HTTP/1.1 200");

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.traceId()).isNotBlank();
              assertThat(singleMdcSnapshot())
                  .containsEntry(TraceIdFilter.MDC_KEY, recorded.traceId());
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется GET /api/hotels с X-Trace-Id: first-id и second-id; записи очищены.
   * Ожидаемый результат: Ответ 200; единственный вызов отелей и единственная запись MDC содержат first-id.
   */
  @Test
  @DisplayName("Из нескольких значений заголовка трассировки выбирается первое")
  void firstOfMultipleTraceHeaderValuesWins() {
    client
        .get()
        .uri("/api/hotels")
        .header(TraceIdFilter.TRACE_HEADER, "first-id", "second-id")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.traceId()).isEqualTo("first-id");
              assertThat(singleMdcSnapshot()).containsEntry(TraceIdFilter.MDC_KEY, "first-id");
            });
  }

  /*
   * Исходные данные: Выполняется POST /api/bookings/user/register без X-Trace-Id; записи очищены.
   * Ожидаемый результат: Ответ 200; единственный вызов бронирований имеет путь /user/register и непустой
   * traceId, совпадающий с MDC; вызовов отелей нет.
   */
  @Test
  @DisplayName("Шлюз создаёт идентификатор трассировки при маршрутизации бронирований")
  void missingHeaderIsGeneratedAndLoggedOnBookingsFamily() {
    client.post().uri("/api/bookings/user/register").exchange().expectStatus().isOk();

    assertThat(BOOKING_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.target()).isEqualTo("/user/register");
              assertThat(recorded.traceId()).isNotBlank();
              assertThat(singleMdcSnapshot())
                  .containsEntry(TraceIdFilter.MDC_KEY, recorded.traceId());
            });
    assertThat(HOTEL_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется POST /api/bookings/user/register с X-Trace-Id: client-xyz; записи очищены.
   * Ожидаемый результат: Ответ 200; единственный вызов бронирований и MDC содержат client-xyz; вызовов отелей
   * нет.
   */
  @Test
  @DisplayName("Идентификатор трассировки клиента сохраняется при маршрутизации бронирований")
  void clientSuppliedHeaderIsPropagatedVerbatimOnBookingsFamily() {
    client
        .post()
        .uri("/api/bookings/user/register")
        .header(TraceIdFilter.TRACE_HEADER, "client-xyz")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(BOOKING_REQUESTS)
        .singleElement()
        .satisfies(recorded -> assertThat(recorded.traceId()).isEqualTo("client-xyz"));
    assertThat(singleMdcSnapshot()).containsEntry(TraceIdFilter.MDC_KEY, "client-xyz");
    assertThat(HOTEL_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется POST /api/rooms/7/confirm-availability без X-Trace-Id; записи заглушек и
   * журнала очищены.
   * Ожидаемый результат: Ответ 404; вызовов сервисов нет, единственная запись MDC содержит непустой traceId.
   */
  @Test
  @DisplayName("Отклонённый внутренний запрос получает идентификатор трассировки в журнале")
  void deniedRequestIsStillTracedInGatewayLogWithNoDownstreamCall() {
    client.post().uri("/api/rooms/7/confirm-availability").exchange().expectStatus().isNotFound();

    // Запрет сохраняется: запрос не достиг целевого сервиса, но запись журнала шлюза,
    // созданная фильтром HIGHEST_PRECEDENCE до проверки запрета,
    // содержит сгенерированный traceId.
    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
    assertThat(singleMdcSnapshot()).containsKeys(TraceIdFilter.MDC_KEY);
    assertThat(singleMdcSnapshot().get(TraceIdFilter.MDC_KEY)).isNotBlank();
  }

  // ---------------------------------------------------------------------
  // Вспомогательные методы.
  // ---------------------------------------------------------------------

  /** Фильтр создаёт ровно одну запись INFO на запрос; перед этим обработчик журнала был очищен. */
  private static Map<String, String> singleMdcSnapshot() {
    assertThat(CAPTURED_MDC_SNAPSHOTS).singleElement();
    return CAPTURED_MDC_SNAPSHOTS.get(0);
  }

  /**
   * Копирует карту MDC синхронно при добавлении записи, в том же потоке, где она установлена, до
   * MDC.remove в блоке finally фильтра; отложенное чтение потеряло бы контекст.
   */
  static class MdcSnapshotAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
      CAPTURED_MDC_SNAPSHOTS.add(Map.copyOf(event.getMDCPropertyMap()));
    }
  }

  private static HttpServer startStub(List<RecordedRequest> sink) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          String target =
              exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
          sink.add(
              new RecordedRequest(
                  exchange.getRequestMethod(),
                  target,
                  exchange.getRequestHeaders().getFirst(TraceIdFilter.TRACE_HEADER)));
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/plain");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static URI stubUri(HttpServer server) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /**
   * Отправляет запрос вручную, чтобы шлюз получил значения заголовков, запрещённые проверками
   * клиента.
   */
  private String sendRaw(String rawRequest) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      socket.getOutputStream().write(rawRequest.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      return reader.readLine();
    }
  }

  record RecordedRequest(String method, String target, String traceId) {}
}
