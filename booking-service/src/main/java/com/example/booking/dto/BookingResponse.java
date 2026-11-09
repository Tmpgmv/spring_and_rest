package com.example.booking.dto;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import java.time.Instant;
import java.time.LocalDate;

public record BookingResponse(
    Long id,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    BookingStatus status,
    Instant createdAt,
    String requestId) {
  public static BookingResponse from(Booking booking, String requestId) {
    return new BookingResponse(
        booking.getId(),
        booking.getRoomId(),
        booking.getStartDate(),
        booking.getEndDate(),
        booking.getStatus(),
        booking.getCreatedAt(),
        requestId);
  }
}
