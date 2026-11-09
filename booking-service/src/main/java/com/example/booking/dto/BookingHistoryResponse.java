package com.example.booking.dto;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;

public record BookingHistoryResponse(
    Long id,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    BookingStatus status,
    Instant createdAt) {
  public static BookingHistoryResponse from(Booking booking) {
    return new BookingHistoryResponse(
        booking.getId(),
        booking.getRoomId(),
        booking.getStartDate(),
        booking.getEndDate(),
        booking.getStatus(),
        booking.getCreatedAt());
  }
}
