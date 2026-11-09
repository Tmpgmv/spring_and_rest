package com.example.booking.service;

import com.example.booking.client.HotelClient;
import com.example.booking.entity.BookingStatus;
import com.example.booking.logging.SagaLog;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.ProcessedRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingCancellationService {
  private static final Logger LOG = LoggerFactory.getLogger(BookingCancellationService.class);
  private final BookingRepository bookings;
  private final ProcessedRequestRepository requests;
  private final HotelClient hotel;

  public BookingCancellationService(
      BookingRepository bookings, ProcessedRequestRepository requests, HotelClient hotel) {
    this.bookings = bookings;
    this.requests = requests;
    this.hotel = hotel;
  }

  // Последовательные повторные отмены ожидают завершения ограниченной попытки освобождения и
  // условной записи.
  @Transactional
  public void cancel(Long id, String username) {
    var booking =
        bookings
            .findOwnedForCancellation(id, username)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    var previous = booking.getStatus();
    if (previous == BookingStatus.CANCELLED) return;
    if (previous == BookingStatus.CONFIRMED) {
      var request = requests.findByBookingId(id).orElseThrow();
      try (var context = new SagaLog(id.toString(), request.getRequestId())) {
        try {
          hotel.release(
              booking.getRoomId(),
              booking.getStartDate(),
              booking.getEndDate(),
              request.getRequestId());
          SagaLog.step(LOG, "release", "SUCCESS");
        } catch (RuntimeException failure) {
          SagaLog.step(LOG, "release", "FAILED");
        }
      }
    }
    // Для бронирований PENDING оркестратор создания отвечает за текущее подтверждение и его
    // освобождение.
    bookings.cancel(id, previous);
  }
}
