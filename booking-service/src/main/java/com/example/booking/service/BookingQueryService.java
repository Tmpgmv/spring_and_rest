package com.example.booking.service;

import com.example.booking.dto.BookingHistoryResponse;
import com.example.booking.repository.BookingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Читает бронирования только в пределах учётной записи вызывающего пользователя. */
@Service
@Transactional(readOnly = true)
public class BookingQueryService {
  private final BookingRepository bookings;

  public BookingQueryService(BookingRepository bookings) {
    this.bookings = bookings;
  }

  public Page<BookingHistoryResponse> list(String username, Pageable pageable) {
    return bookings.findByUserUsername(username, pageable).map(BookingHistoryResponse::from);
  }

  public BookingHistoryResponse get(Long id, String username) {
    return bookings
        .findByIdAndUserUsername(id, username)
        .map(BookingHistoryResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
  }
}
