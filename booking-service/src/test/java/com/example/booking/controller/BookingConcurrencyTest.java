package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.ProcessedRequestRepository;
import com.example.booking.service.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Настоящий стек HTTP/БД бронирования с атомарной HTTP-заглушкой отелей, сохраняющей состояние. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "auth.internal.key=concurrency-test-key",
      "hotel.client.connect-timeout=5s",
      "hotel.client.read-timeout=15s",
      "hotel.client.resilience.timeout=15s",
      "hotel.client.resilience.max-attempts=1",
      "hotel.client.resilience.minimum-calls=100",
      "hotel.client.resilience.window-size=100",
      "booking.orphan.sweep-interval=1d"
    })
@Import(BookingConcurrencyTest.FixedTime.class)
class BookingConcurrencyTest {
  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  private static final int N = 8;
  private static final long ROOM = 47L;
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final ExecutorService HOTEL_THREADS = Executors.newFixedThreadPool(N);
  private static final List<Slot> SLOTS = new ArrayList<>();
  private static final List<String> CONFIRMATIONS = new ArrayList<>();
  private static final AtomicReference<Throwable> SERVER_ERROR = new AtomicReference<>();
  private static volatile CountDownLatch hotelArrivals;
  private static int slotInsertions;
  private static int timesBooked;
  private static int releases;
  private static final HttpServer HOTEL = startHotel();

  @LocalServerPort int port;
  @Autowired JwtTokenService tokens;
  @Autowired BookingRepository bookings;
  @Autowired ProcessedRequestRepository requests;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  record Slot(String requestId, LocalDate start, LocalDate end) {}

  record Result(String requestId, int status, String body) {}

  private static HttpServer startHotel() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(HOTEL_THREADS);
      server.createContext("/api/rooms/", BookingConcurrencyTest::handleHotel);
      server.start();
      return server;
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void handleHotel(HttpExchange exchange) throws IOException {
    int status = 500;
    try {
      assertThat(exchange.getRequestMethod()).isEqualTo("POST");
      assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Key"))
          .isEqualTo("concurrency-test-key");
      var body = JSON.readTree(exchange.getRequestBody());
      var slot =
          new Slot(
              body.get("requestId").asText(),
              LocalDate.parse(body.get("startDate").asText()),
              LocalDate.parse(body.get("endDate").asText()));
      if (exchange.getRequestURI().getPath().endsWith("/release")) {
        synchronized (SLOTS) {
          SLOTS.removeIf(existing -> existing.equals(slot));
          releases++;
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
        return;
      }
      assertThat(exchange.getRequestURI().getPath())
          .isEqualTo("/api/rooms/" + ROOM + "/confirm-availability");
      // Все N запросов должны достичь отелей до завершения любого из них, подтверждая реальную
      // конкурентность.
      hotelArrivals.countDown();
      assertThat(hotelArrivals.await(10, TimeUnit.SECONDS)).isTrue();
      synchronized (SLOTS) {
        CONFIRMATIONS.add(slot.requestId());
        if (SLOTS.stream().anyMatch(existing -> existing.requestId().equals(slot.requestId()))) {
          status = 200; // Идемпотентный повтор: без вставки и увеличения счётчика.
        } else if (SLOTS.stream()
            .anyMatch(
                existing ->
                    existing.start().isBefore(slot.end())
                        && slot.start().isBefore(existing.end()))) {
          status = 409;
        } else {
          SLOTS.add(slot);
          slotInsertions++;
          timesBooked++;
          status = 200;
        }
      }
    } catch (Throwable ex) {
      SERVER_ERROR.compareAndSet(null, ex);
      if (exchange.getRequestURI().getPath().endsWith("/release")) {
        synchronized (SLOTS) {
          releases++;
        }
      }
    }
    try {
      exchange.sendResponseHeaders(status, -1);
    } finally {
      exchange.close();
    }
  }

  @DynamicPropertySource
  static void hotelDiscovery(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.cloud.discovery.client.simple.instances.hotel-service[0].uri",
        () -> "http://127.0.0.1:" + HOTEL.getAddress().getPort());
  }

  @BeforeEach
  void reset() {
    requests.deleteAll();
    bookings.deleteAll();
    synchronized (SLOTS) {
      SLOTS.clear();
      CONFIRMATIONS.clear();
      slotInsertions = timesBooked = releases = 0;
    }
    SERVER_ERROR.set(null);
    hotelArrivals = new CountDownLatch(N);
  }

  @AfterAll
  static void stopHotel() throws InterruptedException {
    HOTEL.stop(0);
    HOTEL_THREADS.shutdownNow();
    assertThat(HOTEL_THREADS.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private List<Result> race(boolean overlapping) throws Exception {
    var pool = Executors.newFixedThreadPool(N);
    var ready = new CountDownLatch(N);
    var start = new CountDownLatch(1);
    String authorization = "Bearer " + tokens.issueToken("user", UserRole.USER);
    LocalDate first = LocalDate.of(2030, 7, 1);
    try {
      List<Future<Result>> futures = new ArrayList<>();
      for (int i = 0; i < N; i++) {
        String id = "race-" + i;
        LocalDate date = first.plusDays(overlapping ? 0 : i * 2L);
        String body =
            JSON.writeValueAsString(
                Map.of(
                    "roomId",
                    ROOM,
                    "startDate",
                    date.toString(),
                    "endDate",
                    date.plusDays(2).toString(),
                    "requestId",
                    id,
                    "autoSelect",
                    false));
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start barrier timed out");
                  var response =
                      client.send(
                          HttpRequest.newBuilder(
                                  URI.create("http://127.0.0.1:" + port + "/booking"))
                              .timeout(Duration.ofSeconds(25))
                              .header("Authorization", authorization)
                              .header("Content-Type", "application/json")
                              .POST(HttpRequest.BodyPublishers.ofString(body))
                              .build(),
                          HttpResponse.BodyHandlers.ofString());
                  return new Result(id, response.statusCode(), response.body());
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Result> results = new ArrayList<>();
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      assertThat(SERVER_ERROR.get()).isNull();
      assertThat(hotelArrivals.getCount()).isZero();
      return results;
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /*
   * Исходные данные: Восемь потоков с разными requestId одновременно бронируют номер 47 на 1–3 июля 2030 года
   * через HTTP-заглушку с атомарной проверкой пересечений.
   * Ожидаемый результат: Один ответ имеет статус 201, семь — 409; сохранены восемь согласованных результатов
   * и бронирований, один слот и одно увеличение счётчика, освобождений нет.
   */
  @Test
  @DisplayName("Из восьми конкурентных бронирований одного периода проходит ровно одно")
  void overlappingBookingsHaveExactlyOneWinnerAndOneSlotInsertion() throws Exception {
    var results = race(true);
    assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
    assertThat(results).filteredOn(r -> r.status() == 409).hasSize(N - 1);
    assertOutcomes(results);
    String winner =
        results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().requestId();
    synchronized (SLOTS) {
      assertThat(SLOTS).singleElement().extracting(Slot::requestId).isEqualTo(winner);
      assertThat(slotInsertions).isEqualTo(1);
      assertThat(timesBooked).isEqualTo(1);
    }
  }

  /*
   * Исходные данные: Восемь потоков с разными requestId бронируют номер 47 на последовательные
   * непересекающиеся двухдневные периоды с 1 июля 2030 года.
   * Ожидаемый результат: Все восемь ответов имеют статус 201; сохранены восемь результатов, подтверждённых
   * бронирований и слотов, счётчик увеличен восемь раз, освобождений нет.
   */
  @Test
  @DisplayName("Смежные конкурентные бронирования успешно создают отдельные слоты")
  void adjacentNonOverlappingBookingsAllSucceedAndIncrementOnceEach() throws Exception {
    var results = race(false);
    assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
    assertOutcomes(results);
    synchronized (SLOTS) {
      assertThat(SLOTS).hasSize(N);
      assertThat(slotInsertions).isEqualTo(N);
      assertThat(timesBooked).isEqualTo(N);
    }
  }

  /*
   * Исходные данные: Создаётся бронирование номера 47 на 1–3 июля 2030 года с requestId=before-cancel; четыре
   * DELETE выполняются одновременно, затем период бронируется с requestId=after-cancel.
   * Ожидаемый результат: Все отмены дают 204, статус становится CANCELLED, слот освобождён один раз и счётчик
   * остаётся 1; повторное бронирование даёт 201, один слот и счётчик 2.
   */
  @Test
  @DisplayName("Конкурентная отмена освобождает слот один раз и допускает повторное бронирование")
  void bookThenConcurrentCancelFreesRoomForRebookingWithoutDecrementingDemand() throws Exception {
    hotelArrivals = new CountDownLatch(0);
    String authorization = "Bearer " + tokens.issueToken("user", UserRole.USER);
    LocalDate start = LocalDate.of(2030, 7, 1);
    String body =
        JSON.writeValueAsString(
            Map.of(
                "roomId",
                ROOM,
                "startDate",
                start.toString(),
                "endDate",
                start.plusDays(2).toString(),
                "requestId",
                "before-cancel",
                "autoSelect",
                false));
    var create =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/booking"))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json");
    var booked =
        client.send(
            create.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(booked.statusCode()).isEqualTo(201);
    Long id = JSON.readTree(booked.body()).get("id").asLong();
    var cancel =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/booking/" + id))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", authorization)
            .DELETE()
            .build();
    var cancellations =
        IntStream.range(0, 4)
            .mapToObj(i -> client.sendAsync(cancel, HttpResponse.BodyHandlers.ofString()))
            .toList();
    for (var result : cancellations)
      assertThat(result.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
    assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
    synchronized (SLOTS) {
      assertThat(SLOTS).isEmpty();
      assertThat(releases).isEqualTo(1);
      assertThat(timesBooked).isEqualTo(1);
    }
    var rebooked =
        client.send(
            create
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        body.replace("before-cancel", "after-cancel")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(rebooked.statusCode()).isEqualTo(201);
    synchronized (SLOTS) {
      assertThat(SLOTS).hasSize(1);
      assertThat(timesBooked).isEqualTo(2);
    }
    assertThat(SERVER_ERROR.get()).isNull();
  }

  private void assertOutcomes(List<Result> results) throws Exception {
    assertThat(bookings.count()).isEqualTo(N);
    assertThat(requests.count()).isEqualTo(N);
    for (var result : results) {
      var processed = requests.findByRequestId(result.requestId()).orElseThrow();
      var booking = bookings.findById(processed.getBooking().getId()).orElseThrow();
      assertThat(booking.getStatus())
          .isEqualTo(result.status() == 201 ? BookingStatus.CONFIRMED : BookingStatus.CANCELLED);
      assertThat(processed.getHttpStatus()).isEqualTo(result.status());
      assertThat(processed.getResponseSnapshot()).isEqualTo(result.body());
      if (result.status() == 201) {
        assertThat(JSON.readTree(result.body()).get("status").asText()).isEqualTo("CONFIRMED");
      }
    }
    synchronized (SLOTS) {
      assertThat(CONFIRMATIONS)
          .containsExactlyInAnyOrderElementsOf(
              IntStream.range(0, N).mapToObj(i -> "race-" + i).toList());
      assertThat(new HashSet<>(CONFIRMATIONS)).hasSize(N);
      assertThat(releases).isZero();
    }
  }
}
