package com.example.booking.saga;

import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.ProcessedRequest;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.ProcessedRequestRepository;
import com.example.booking.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Каждый метод выполняется в короткой локальной транзакции; оркестратор не удерживает её во время
 * HTTP-вызовов.
 */
@Service
public class BookingTransactions {
  private final BookingRepository bookings;
  private final ProcessedRequestRepository requests;
  private final UserRepository users;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final EntityManager entityManager;

  public BookingTransactions(
      BookingRepository bookings,
      ProcessedRequestRepository requests,
      UserRepository users,
      ObjectMapper mapper,
      Clock clock,
      EntityManager entityManager) {
    this.bookings = bookings;
    this.requests = requests;
    this.users = users;
    this.mapper = mapper;
    this.clock = clock;
    this.entityManager = entityManager;
  }

  @Transactional
  public BookingWork start(String username, CreateBookingRequest request, String requestId) {
    var user =
        users
            .findByUsername(username)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found"));
    var booking =
        bookings.save(
            new Booking(
                user,
                request.autoSelect() ? null : request.roomId(),
                request.startDate(),
                request.endDate(),
                clock.instant()));
    requests.saveAndFlush(new ProcessedRequest(requestId, booking, clock.instant()));
    return record(requests.findByRequestId(requestId).orElseThrow()).work();
  }

  @Transactional(readOnly = true)
  public Optional<BookingRecord> find(String requestId) {
    entityManager.clear();
    return requests.findByRequestId(requestId).map(this::record);
  }

  @Transactional
  public BookingRecord selectRoom(String requestId, Long roomId) {
    var processed = requests.findLockedByRequestId(requestId).orElseThrow();
    if (processed.getBooking().getStatus() == BookingStatus.PENDING) {
      processed.getBooking().selectRoom(roomId);
    } else if (processed.getBooking().getStatus() == BookingStatus.CANCELLED
        && processed.getResponseSnapshot() == null) {
      return finish(requestId, BookingStatus.CANCELLED, 409, null);
    }
    return record(processed);
  }

  @Transactional
  public BookingRecord finish(
      String requestId, BookingStatus target, int status, String errorBody) {
    var processed = requests.findLockedByRequestId(requestId).orElseThrow();
    var booking = processed.getBooking();
    int completed = bookings.completePending(booking.getId(), target);
    entityManager.refresh(booking);
    if (completed == 1) {
      String snapshot =
          target == BookingStatus.CONFIRMED
              ? json(BookingResponse.from(booking, requestId))
              : errorBody;
      processed.complete(status, snapshot, clock.instant());
    }
    if (booking.getStatus() == BookingStatus.CANCELLED && processed.getResponseSnapshot() == null) {
      processed.complete(
          409,
          json(
              new com.example.booking.dto.ErrorDTO(
                  clock.instant(), 409, "Conflict", "Booking was cancelled", "/booking", "", null)),
          clock.instant());
    }
    return record(processed);
  }

  @Transactional
  public Optional<BookingWork> cancelOrphan(
      String requestId, java.time.Instant cutoff, String errorBody) {
    var processed = requests.findLockedByRequestId(requestId).orElse(null);
    if (processed == null) return Optional.empty();
    var booking = processed.getBooking();
    if (booking.getStatus() != BookingStatus.PENDING || !booking.getCreatedAt().isBefore(cutoff)) {
      return Optional.empty();
    }
    // Используем ту же условную запись конечного состояния и снимок повторного ответа, что и поток
    // запроса.
    return Optional.of(finish(requestId, BookingStatus.CANCELLED, 503, errorBody).work());
  }

  private BookingRecord record(ProcessedRequest processed) {
    var b = processed.getBooking();
    var work =
        new BookingWork(
            b.getId(),
            b.getUser().getUsername(),
            b.getRoomId(),
            b.getStartDate(),
            b.getEndDate(),
            processed.getRequestId());
    var outcome =
        processed.getResponseSnapshot() == null
            ? null
            : new BookingOutcome(processed.getHttpStatus(), processed.getResponseSnapshot());
    return new BookingRecord(work, outcome);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Cannot serialize booking outcome", ex);
    }
  }
}
