package com.example.booking.client;

import java.time.LocalDate;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class RestHotelClient implements HotelClient {
  private final RestClient client;
  private final String key;

  public RestHotelClient(
      @Qualifier("hotelRestClientBuilder") RestClient.Builder builder,
      @Value("${auth.internal.key}") String key) {
    this.client = builder.baseUrl("http://hotel-service").build();
    this.key = key;
  }

  @Override
  public java.util.List<RecommendedRoom> recommend(
      LocalDate startDate, LocalDate endDate, String authorization) {
    try {
      var rooms =
          client
              .get()
              .uri(
                  uri ->
                      uri.path("/api/rooms/recommend")
                          .queryParam("startDate", startDate)
                          .queryParam("endDate", endDate)
                          .build())
              .header("Authorization", authorization)
              .header("X-Trace-Id", MDC.get("traceId") == null ? "" : MDC.get("traceId"))
              .retrieve()
              .body(RecommendedRoom[].class);
      if (rooms == null)
        throw new HotelCallException(0, new IllegalStateException("Missing recommendations"));
      return java.util.List.of(rooms);
    } catch (RestClientResponseException ex) {
      throw new HotelCallException(ex.getStatusCode().value(), ex);
    } catch (IllegalStateException ex) {
      throw discoveryFailure(ex);
    } catch (RestClientException ex) {
      throw new HotelCallException(ex instanceof ResourceAccessException ? 503 : 0, ex);
    }
  }

  @Override
  public void confirm(Long roomId, LocalDate startDate, LocalDate endDate, String requestId) {
    call(roomId, "confirm-availability", startDate, endDate, requestId);
  }

  @Override
  public void release(Long roomId, LocalDate startDate, LocalDate endDate, String requestId) {
    call(roomId, "release", startDate, endDate, requestId);
  }

  private void call(
      Long roomId, String operation, LocalDate startDate, LocalDate endDate, String requestId) {
    try {
      client
          .post()
          .uri("/api/rooms/{id}/{operation}", roomId, operation)
          .header("X-Internal-Key", key)
          .header("X-Booking-Id", MDC.get("bookingId") == null ? "" : MDC.get("bookingId"))
          .header("X-Trace-Id", MDC.get("traceId") == null ? "" : MDC.get("traceId"))
          .body(new SlotRequest(startDate, endDate, requestId))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException ex) {
      throw new HotelCallException(ex.getStatusCode().value(), ex);
    } catch (IllegalStateException ex) {
      throw discoveryFailure(ex);
    } catch (RestClientException ex) {
      throw new HotelCallException(ex instanceof ResourceAccessException ? 503 : 0, ex);
    }
  }

  private RuntimeException discoveryFailure(IllegalStateException ex) {
    // Spring Cloud сообщает об отсутствии экземпляров исключением без отдельного типа.
    // Проверяем источник, чтобы не скрыть ошибку приложения с похожим сообщением.
    var stack = ex.getStackTrace();
    if ("No instances available for hotel-service".equals(ex.getMessage())
        && stack.length > 0
        && stack[0]
            .getClassName()
            .equals(
                "org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient")
        && stack[0].getMethodName().equals("execute")) {
      return new HotelCallException(503, ex);
    }
    return ex;
  }

  private record SlotRequest(LocalDate startDate, LocalDate endDate, String requestId) {}
}
