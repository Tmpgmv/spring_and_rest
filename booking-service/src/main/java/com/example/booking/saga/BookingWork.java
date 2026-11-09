package com.example.booking.saga;

import java.time.LocalDate;

public record BookingWork(
    Long bookingId,
    String username,
    Long roomId,
    LocalDate startDate,
    LocalDate endDate,
    String requestId) {}
