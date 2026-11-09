package com.example.booking.saga;

import com.example.booking.client.HotelCallException;
import com.example.booking.client.HotelClient;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.dto.ErrorDTO;
import com.example.booking.entity.BookingStatus;
import com.example.booking.logging.SagaLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingSaga {
  private static final Logger LOG = LoggerFactory.getLogger(BookingSaga.class);
  private final BookingTransactions transactions;
  private final HotelClient hotel;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final Duration replayWait;
  private final Duration replayPoll;

  public BookingSaga(
      BookingTransactions transactions,
      HotelClient hotel,
      Clock clock,
      ObjectMapper mapper,
      @Value("${booking.replay.wait-timeout}") Duration replayWait,
      @Value("${booking.replay.poll-interval}") Duration replayPoll) {
    this.transactions = transactions;
    this.hotel = hotel;
    this.clock = clock;
    this.mapper = mapper;
    this.replayWait = replayWait;
    this.replayPoll = replayPoll;
  }

  public BookingOutcome create(
      String username, CreateBookingRequest request, String authorization) {
    String requestId =
        request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
    var existing = transactions.find(requestId);
    if (existing.isPresent()) return replay(username, existing.get());
    List<ErrorDTO.FieldError> fields = new ArrayList<>();
    if (request.startDate().isBefore(LocalDate.now(clock)))
      fields.add(new ErrorDTO.FieldError("startDate", "must not be in the past"));
    if (!request.startDate().isBefore(request.endDate()))
      fields.add(new ErrorDTO.FieldError("endDate", "must be after startDate"));
    if (!request.autoSelect() && (request.roomId() == null || request.roomId() <= 0))
      fields.add(new ErrorDTO.FieldError("roomId", "must be a positive room ID"));
    if (!fields.isEmpty()) return error(400, "Validation failed", fields);
    BookingWork work;
    try {
      work = transactions.start(username, request, requestId);
    } catch (DataIntegrityViolationException ex) {
      var concurrent = transactions.find(requestId);
      if (concurrent.isEmpty()) throw ex;
      return replay(username, concurrent.get());
    }
    try (var context = new SagaLog(work.bookingId().toString(), work.requestId())) {
      step(work, "pending", "PENDING");
      try {
        if (request.autoSelect()) {
          var candidates = hotel.recommend(work.startDate(), work.endDate(), authorization);
          boolean confirmed = false;
          for (var candidate : candidates) {
            var selected = transactions.selectRoom(requestId, candidate.id());
            if (selected.outcome() != null) return selected.outcome();
            work = selected.work();
            try {
              hotel.confirm(work.roomId(), work.startDate(), work.endDate(), requestId);
              confirmed = true;
              break;
            } catch (HotelCallException ex) {
              if (ex.getStatus() != 409) throw ex;
              step(work, "confirm", "REJECTED_409");
            }
          }
          if (!confirmed)
            throw new HotelCallException(
                409, new IllegalStateException("No available candidate rooms"));
        } else {
          hotel.confirm(work.roomId(), work.startDate(), work.endDate(), work.requestId());
        }
      } catch (HotelCallException ex) {
        return cancelAfterFailure(work, ex);
      }
      return finishConfirmed(work);
    }
  }

  private BookingOutcome cancelAfterFailure(BookingWork work, HotelCallException ex) {
    String requestId = work.requestId();
    step(work, "confirm", "REJECTED_" + ex.getStatus());
    BookingOutcome failure;
    if (ex.getStatus() == 409) {
      failure = error(409, "Room is unavailable for the requested dates", null);
    } else if (ex.getStatus() == 404) {
      failure =
          error(
              400,
              "Validation failed",
              List.of(new ErrorDTO.FieldError("roomId", "does not exist")));
    } else {
      failure = error(503, "Hotel Service is unavailable", null);
    }
    var result =
        transactions.finish(requestId, BookingStatus.CANCELLED, failure.status(), failure.body());
    step(work, "complete", "CANCELLED");
    if (work.roomId() != null && ex.getStatus() != 409 && ex.getStatus() != 404) {
      try {
        hotel.release(work.roomId(), work.startDate(), work.endDate(), requestId);
        step(work, "release", "SUCCESS");
      } catch (HotelCallException releaseFailure) {
        step(work, "release", "FAILED");
      }
    }
    return result.outcome() == null
        ? error(503, "Booking outcome is not yet available", null)
        : result.outcome();
  }

  private BookingOutcome finishConfirmed(BookingWork work) {
    String requestId = work.requestId();
    step(work, "confirm", "SUCCESS");
    var result = transactions.finish(requestId, BookingStatus.CONFIRMED, 201, null);
    if (result.outcome() != null && result.outcome().status() != 201) {
      try {
        hotel.release(work.roomId(), work.startDate(), work.endDate(), work.requestId());
        step(work, "release", "SUCCESS");
      } catch (HotelCallException failure) {
        step(work, "release", "FAILED");
      }
    }
    step(
        work,
        "complete",
        result.outcome() != null && result.outcome().status() == 201 ? "CONFIRMED" : "UNCHANGED");
    return result.outcome() == null
        ? error(503, "Booking outcome is not yet available", null)
        : result.outcome();
  }

  private BookingOutcome replay(String username, BookingRecord record) {
    if (!record.work().username().equals(username)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Request ID already belongs to another user");
    }
    long start = System.nanoTime();
    while (record.outcome() == null && System.nanoTime() - start < replayWait.toNanos()) {
      if (Thread.currentThread().isInterrupted()) break;
      LockSupport.parkNanos(replayPoll.toNanos());
      record = transactions.find(record.work().requestId()).orElseThrow();
    }
    step(record.work(), "replay", record.outcome() == null ? "PENDING" : "STORED_OUTCOME");
    return record.outcome() == null
        ? error(503, "Booking is still processing; retry with the same requestId", null)
        : record.outcome();
  }

  private BookingOutcome error(int status, String message, List<ErrorDTO.FieldError> fields) {
    var error =
        new ErrorDTO(
            clock.instant(),
            status,
            HttpStatus.valueOf(status).getReasonPhrase(),
            message,
            "/booking",
            traceId(),
            fields);
    try {
      return new BookingOutcome(status, mapper.writeValueAsString(error));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Cannot serialize booking error", ex);
    }
  }

  private static String traceId() {
    return MDC.get("traceId") == null ? "" : MDC.get("traceId");
  }

  private void step(BookingWork work, String step, String outcome) {
    try (var context = new SagaLog(work.bookingId().toString(), work.requestId())) {
      SagaLog.step(LOG, step, outcome);
    }
  }
}
