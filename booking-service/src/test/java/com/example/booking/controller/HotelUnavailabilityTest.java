package com.example.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.ProcessedRequestRepository;
import com.example.booking.service.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.loadbalancer.cache.enabled=false",
      "hotel.client.connect-timeout=100ms",
      "hotel.client.read-timeout=100ms",
      "hotel.client.resilience.timeout=1s",
      "hotel.client.resilience.max-attempts=2",
      "hotel.client.resilience.initial-backoff=10ms",
      "hotel.client.resilience.window-size=1000",
      "hotel.client.resilience.minimum-calls=1000"
    })
@AutoConfigureMockMvc
@Import(HotelUnavailabilityTest.Configuration.class)
@Timeout(15)
class HotelUnavailabilityTest {
  static volatile String failure;
  static volatile int stoppedPort;
  static final List<String> ATTEMPTS = new CopyOnWriteArrayList<>();
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JwtTokenService tokens;
  @Autowired BookingRepository bookings;
  @Autowired ProcessedRequestRepository requests;

  @TestConfiguration
  @LoadBalancerClient(name = "hotel-service", configuration = Instances.class)
  static class Configuration {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    static BeanPostProcessor recordAttempts() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (name.equals("hotelRestClientBuilder") && bean instanceof RestClient.Builder builder) {
            builder.requestInterceptors(
                interceptors ->
                    interceptors.add(
                        0,
                        (request, body, execution) -> {
                          ATTEMPTS.add(request.getURI().getPath());
                          return execution.execute(request, body);
                        }));
          }
          return bean;
        }
      };
    }
  }

  static class Instances {
    @Bean
    ServiceInstanceListSupplier instances() {
      return new ServiceInstanceListSupplier() {
        @Override
        public String getServiceId() {
          return "hotel-service";
        }

        @Override
        public Flux<List<ServiceInstance>> get() {
          if (failure.equals("programming")) {
            throw new IllegalStateException("No instances available for hotel-service");
          }
          return Flux.just(
              failure.equals("missing")
                  ? List.of()
                  : List.of(
                      new DefaultServiceInstance(
                          "stopped", "hotel-service", "127.0.0.1", stoppedPort, false)));
        }
      };
    }
  }

  @BeforeEach
  void reset() throws Exception {
    requests.deleteAll();
    bookings.deleteAll();
    ATTEMPTS.clear();
    try (var socket = new ServerSocket(0)) {
      stoppedPort = socket.getLocalPort();
    }
  }

  String payload(boolean autoSelect) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "roomId",
            47,
            "autoSelect",
            autoSelect,
            "startDate",
            "2030-06-10",
            "endDate",
            "2030-06-15",
            "requestId",
            "unavailable"));
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
    return post("/booking")
        .header("Authorization", "Bearer " + tokens.issueToken("user", UserRole.USER))
        .header("X-Trace-Id", "unavailable-trace")
        .contentType("application/json")
        .content(body);
  }

  /*
   * Исходные данные: Параметр mode задаёт отсутствие экземпляров missing либо остановленный сервер stopped;
   * autoSelect задаёт подбор или номер 47; requestId=unavailable, период 10–15 июня 2030 года, максимум две
   * попытки.
   * Ожидаемый результат: Все комбинации дают сохранённый ErrorDTO 503 и одно CANCELLED-бронирование; подбор
   * вызывает две рекомендации, явный номер — два подтверждения и два освобождения; повтор возвращает тот же
   * ответ без новых вызовов.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Недоступность отелей отменяет бронирование и сохраняет ответ для повтора")
  @CsvSource({"missing,true", "missing,false", "stopped,true", "stopped,false"})
  void unavailableHotelCancelsWithBoundedRetriesAndReplaysSaved503(String mode, boolean autoSelect)
      throws Exception {
    failure = mode;
    var body = payload(autoSelect);
    var original =
        mvc.perform(request(body))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error").value("Service Unavailable"))
            .andExpect(jsonPath("$.message").value("Hotel Service is unavailable"))
            .andExpect(jsonPath("$.path").value("/booking"))
            .andExpect(jsonPath("$.traceId").value("unavailable-trace"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(bookings.findAll())
        .singleElement()
        .satisfies(
            booking -> {
              assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
              assertThat(booking.getRoomId()).isEqualTo(autoSelect ? null : 47L);
            });
    assertThat(requests.findByRequestId("unavailable"))
        .hasValueSatisfying(
            saved -> {
              assertThat(saved.getHttpStatus()).isEqualTo(503);
              assertThat(saved.getResponseSnapshot()).isEqualTo(original);
            });
    if (autoSelect) {
      assertThat(ATTEMPTS).containsExactly("/api/rooms/recommend", "/api/rooms/recommend");
    } else {
      assertThat(ATTEMPTS)
          .containsExactly(
              "/api/rooms/47/confirm-availability",
              "/api/rooms/47/confirm-availability",
              "/api/rooms/47/release",
              "/api/rooms/47/release");
    }
    var attempts = List.copyOf(ATTEMPTS);
    failure = "programming";
    mvc.perform(request(body))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().string(original));
    assertThat(ATTEMPTS).containsExactlyElementsOf(attempts);
    assertThat(bookings.count()).isEqualTo(1);
    assertThat(requests.count()).isEqualTo(1);
  }

  /*
   * Исходные данные: Поставщик экземпляров бросает IllegalStateException с текстом о недоступных экземплярах;
   * параметр autoSelect задаёт подбор или явный номер 47.
   * Ожидаемый результат: Каждый вариант даёт 500 с сообщением Internal server error; фиксируется ровно одна
   * попытка без повторов.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Программная ошибка поиска экземпляров не маскируется недоступностью отелей")
  @ValueSource(booleans = {true, false})
  void unrelatedIllegalStateWithSameMessageRemains500WithoutRetries(boolean autoSelect)
      throws Exception {
    failure = "programming";
    mvc.perform(request(payload(autoSelect)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("Internal server error"));
    assertThat(ATTEMPTS).hasSize(1);
  }
}
