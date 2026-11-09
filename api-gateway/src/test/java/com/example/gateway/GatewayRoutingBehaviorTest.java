package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Проверяет маршрутизацию через заглушки: группы с передачей исходного пути сохраняют /api/…, а
 * bookings удаляет только префикс /api/bookings, сохраняя строку запроса. Заголовок Authorization
 * передаётся побайтно без изменений на каждом маршруте. В application.yml остаются адреса lb://,
 * проверяемые GatewayRoutesConfigTest. Здесь адреса заменяются на заглушки JDK HttpServer с
 * динамическими портами через GatewayProperties при обновлении контекста: индексированные
 * переопределения @DynamicPropertySource скрывают весь список маршрутов при привязке в Gateway 4.3.
 * Eureka отключена для независимости от окружения.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "eureka.client.enabled=false")
@ContextConfiguration(initializers = GatewayRoutingBehaviorTest.StubRouteUrisInitializer.class)
class GatewayRoutingBehaviorTest {

  private static final String TOKEN = "Bearer test-token";

  private static final List<RecordedRequest> HOTEL_REQUESTS = new CopyOnWriteArrayList<>();
  private static final List<RecordedRequest> BOOKING_REQUESTS = new CopyOnWriteArrayList<>();

  private static HttpServer hotelStub;
  private static HttpServer bookingStub;

  @Autowired private WebTestClient client;

  @LocalServerPort private int port;

  @BeforeEach
  void clearStubRecordings() {
    HOTEL_REQUESTS.clear();
    BOOKING_REQUESTS.clear();
  }

  @BeforeAll
  static void startStubs() throws IOException {
    hotelStub = startStub(HOTEL_REQUESTS);
    bookingStub = startStub(BOOKING_REQUESTS);
  }

  @AfterAll
  static void stopStubs() {
    if (hotelStub != null) {
      hotelStub.stop(0);
    }
    if (bookingStub != null) {
      bookingStub.stop(0);
    }
  }

  /**
   * Перенаправляет три маршрута из YAML на эхо-заглушки после привязки, до первого запроса.
   * Маршруты загружаются лениво, поэтому кэш ещё не создан.
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

  /*
   * Исходные данные: Списки вызовов заглушек очищены; выполняется GET /api/hotels/x?y=1 с Authorization:
   * Bearer test-token.
   * Ожидаемый результат: Ответ 200 с телом ok; заглушка отелей получает один GET с исходным путём и токеном,
   * заглушка бронирований не вызывается.
   */
  @Test
  @DisplayName("Маршрут отелей сохраняет путь, параметры и заголовок авторизации")
  void hotelsRouteIsPassthroughAndForwardsAuthorization() {
    client
        .get()
        .uri("/api/hotels/x?y=1")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("ok");

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.method()).isEqualTo("GET");
              assertThat(recorded.target()).isEqualTo("/api/hotels/x?y=1");
              assertThat(recorded.authorization()).isEqualTo(TOKEN);
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Заглушки запущены, записи очищены; выполняется GET /api/rooms/7 с Authorization: Bearer
   * test-token.
   * Ожидаемый результат: Ответ 200; заглушка отелей получает один GET /api/rooms/7 с исходным токеном,
   * вызовов бронирований нет.
   */
  @Test
  @DisplayName("Маршрут номеров передаёт исходный путь и авторизацию сервису отелей")
  void roomsRouteIsPassthroughAndForwardsAuthorization() {
    client
        .get()
        .uri("/api/rooms/7")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.method()).isEqualTo("GET");
              assertThat(recorded.target()).isEqualTo("/api/rooms/7");
              assertThat(recorded.authorization()).isEqualTo(TOKEN);
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется POST /api/bookings/user/register?q=1 с Authorization: Bearer test-token;
   * записи заглушек очищены.
   * Ожидаемый результат: Ответ 200; заглушка бронирований получает один POST /user/register?q=1 с токеном,
   * вызовов отелей нет.
   */
  @Test
  @DisplayName("Маршрут бронирований удаляет префикс и сохраняет параметры и авторизацию")
  void bookingsRouteStripsPrefixAndKeepsQuery() {
    client
        .post()
        .uri("/api/bookings/user/register?q=1")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(BOOKING_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.method()).isEqualTo("POST");
              assertThat(recorded.target()).isEqualTo("/user/register?q=1");
              assertThat(recorded.authorization()).isEqualTo(TOKEN);
            });
    assertThat(HOTEL_REQUESTS).isEmpty();
  }

  // ---------------------------------------------------------------------
  // Запрет внутренних операций: два служебных пути саги
  // возвращают 404 на шлюзе и не достигают целевого сервиса;
  // остальные пути /api/rooms/** маршрутизируются как обычно.
  // ---------------------------------------------------------------------

  /*
   * Исходные данные: Выполняется POST /api/rooms/7/confirm-availability с тестовым токеном; записи заглушек
   * пусты.
   * Ожидаемый результат: Ответ 404; ни одна заглушка не получает запрос.
   */
  @Test
  @DisplayName("Шлюз запрещает внешнее подтверждение доступности без вызова сервисов")
  void confirmAvailabilitySubpathIsDeniedWithNoDownstreamCall() {
    client
        .post()
        .uri("/api/rooms/7/confirm-availability")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется POST /api/rooms/7/release с тестовым токеном; записи заглушек пусты.
   * Ожидаемый результат: Ответ 404; ни одна заглушка не вызывается.
   */
  @Test
  @DisplayName("Шлюз запрещает внешний вызов освобождения номера")
  void releaseSubpathIsDeniedWithNoDownstreamCall() {
    client
        .post()
        .uri("/api/rooms/7/release")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняются GET /api/rooms/7/confirm-availability и DELETE /api/rooms/7/release.
   * Ожидаемый результат: Оба ответа имеют статус 404; списки вызовов обеих заглушек пусты.
   */
  @Test
  @DisplayName("Запрет внутренних путей действует для разных HTTP-методов")
  void denyIsMethodAgnosticOnInternalSubpaths() {
    client.get().uri("/api/rooms/7/confirm-availability").exchange().expectStatus().isNotFound();

    client.delete().uri("/api/rooms/7/release").exchange().expectStatus().isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется POST /api/rooms/7/confirm-availability?requestId=abc-123.
   * Ожидаемый результат: Ответ 404; заглушка отелей не вызывается.
   */
  @Test
  @DisplayName("Параметры запроса не обходят запрет внутреннего подтверждения")
  void denyIgnoresQueryStringOnInternalSubpaths() {
    client
        .post()
        .uri("/api/rooms/7/confirm-availability?requestId=abc-123")
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Абсолютные URI сохраняют %2F; выполняются POST /api/rooms/7%2Fconfirm-availability и
   * DELETE /api/rooms/7%2Frelease.
   * Ожидаемый результат: Оба ответа имеют статус 404; вызовов обеих заглушек нет.
   */
  @Test
  @DisplayName("Кодированная косая черта не позволяет обойти запрет внутренних операций")
  void encodedSlashOnInternalSubpathIsStillDeniedWithNoDownstreamCall() {
    // Кодирование %2F в сегменте {id} не должно позволять обход запрета:
    // фильтр декодирует исходный путь перед сопоставлением с шаблоном.
    // rawUri() строит абсолютный URI, сохраняя исходное %2F при передаче;
    // строковый шаблон перекодировал бы его в %252F и скрыл обход,
    // а относительный URI не разрешается относительно тестового сервера.
    client
        .post()
        .uri(rawUri("/api/rooms/7%2Fconfirm-availability"))
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isNotFound();

    client.delete().uri(rawUri("/api/rooms/7%2Frelease")).exchange().expectStatus().isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется GET по абсолютному URI /api/rooms/7/%63onfirm-availability с сохранением
   * кодирования.
   * Ожидаемый результат: Ответ 404; ни одна заглушка не вызывается.
   */
  @Test
  @DisplayName("Кодированная буква в названии внутренней операции не обходит запрет")
  void percentEncodedCharactersInInternalSubpathAreStillDenied() {
    // Закодированные буквы в сегменте операции тоже декодируются до сопоставления
    // (/…/%63onfirm-availability → /…/confirm-availability → запрет).
    client
        .get()
        .uri(rawUri("/api/rooms/7/%63onfirm-availability"))
        .exchange()
        .expectStatus()
        .isNotFound();

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется GET /api/rooms/recommend с startDate=2026-06-01, endDate=2026-06-03 и
   * тестовым токеном.
   * Ожидаемый результат: Ответ 200; заглушка отелей получает один GET с исходным путём и датами, вызовов
   * бронирований нет.
   */
  @Test
  @DisplayName("Рекомендация номеров остаётся доступной при запрете внутренних операций")
  void nonInternalRoomsPathsStillRouteAfterDenyIsAdded() {
    client
        .get()
        .uri("/api/rooms/recommend?startDate=2026-06-01&endDate=2026-06-03")
        .header("Authorization", TOKEN)
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(HOTEL_REQUESTS)
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.method()).isEqualTo("GET");
              assertThat(recorded.target())
                  .isEqualTo("/api/rooms/recommend?startDate=2026-06-01&endDate=2026-06-03");
            });
    assertThat(BOOKING_REQUESTS).isEmpty();
  }

  /*
   * Исходные данные: Выполняется GET /api/unknown; списки вызовов заглушек очищены.
   * Ожидаемый результат: Ответ 404 содержит поле path со значением /api/unknown; обе заглушки не вызываются.
   */
  @Test
  @DisplayName("Неизвестный маршрут возвращает 404 с исходным путём без вызова сервисов")
  void unmatchedRouteReturnsGateway404WithoutDownstreamCall() {
    client
        .get()
        .uri("/api/unknown")
        .exchange()
        .expectStatus()
        .isNotFound()
        // Стандартное тело ошибки Boot 3.5 формируется самим шлюзом:
        // поле path содержит путь запроса, не совпавший с маршрутом.
        // Запрос не достиг ни одного целевого сервиса.
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("\"path\":\"/api/unknown\""));

    assertThat(HOTEL_REQUESTS).isEmpty();
    assertThat(BOOKING_REQUESTS).isEmpty();
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
                  exchange.getRequestHeaders().getFirst("Authorization")));
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
   * Абсолютный URI с реальным портом шлюза сохраняет исходные байты пути, включая кодированные
   * косые черты.
   */
  private URI rawUri(String rawPath) {
    return URI.create("http://localhost:" + port + rawPath);
  }

  record RecordedRequest(String method, String target, String authorization) {}
}
