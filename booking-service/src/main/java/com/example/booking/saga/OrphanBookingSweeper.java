package com.example.booking.saga;

import com.example.booking.client.HotelClient;
import com.example.booking.dto.ErrorDTO;
import com.example.booking.logging.SagaLog;
import com.example.booking.repository.ProcessedRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrphanBookingSweeper {
  private static final Logger LOG = LoggerFactory.getLogger(OrphanBookingSweeper.class);
  private final ProcessedRequestRepository requests;
  private final BookingTransactions transactions;
  private final HotelClient hotel;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final Duration threshold;

  public OrphanBookingSweeper(
      ProcessedRequestRepository requests,
      BookingTransactions transactions,
      HotelClient hotel,
      Clock clock,
      ObjectMapper mapper,
      @Value("${booking.orphan.threshold:5m}") Duration threshold) {
    if (threshold.isNegative() || threshold.isZero())
      throw new IllegalArgumentException("Orphan threshold must be positive");
    this.requests = requests;
    this.transactions = transactions;
    this.hotel = hotel;
    this.clock = clock;
    this.mapper = mapper;
    this.threshold = threshold;
  }

  @Scheduled(
      fixedDelayString = "${booking.orphan.sweep-interval:1m}",
      initialDelayString = "${booking.orphan.sweep-interval:1m}")
  public void sweep() {
    var cutoff = clock.instant().minus(threshold);
    String body;
    try {
      body =
          mapper.writeValueAsString(
              new ErrorDTO(
                  clock.instant(),
                  503,
                  "Service Unavailable",
                  "Booking expired while processing",
                  "/booking",
                  "",
                  null));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Cannot serialize orphan outcome", ex);
    }
    for (String requestId : requests.findOrphanRequestIds(cutoff)) {
      try {
        // Локальная отмена фиксируется до удалённого вызова; блокировки БД не удерживаются во время
        // HTTP.
        transactions
            .cancelOrphan(requestId, cutoff, body)
            .ifPresent(
                work -> {
                  try (var context = new SagaLog(work.bookingId().toString(), work.requestId())) {
                    SagaLog.step(LOG, "orphan", "CANCELLED");
                    if (work.roomId() != null) {
                      try {
                        hotel.release(
                            work.roomId(), work.startDate(), work.endDate(), work.requestId());
                        SagaLog.step(LOG, "release", "SUCCESS");
                      } catch (RuntimeException failure) {
                        SagaLog.step(LOG, "release", "FAILED");
                        throw failure;
                      }
                    }
                  }
                });
      } catch (RuntimeException ex) {
        LOG.warn("Orphan cleanup failed for requestId={}; continuing sweep", requestId, ex);
      }
    }
  }
}
