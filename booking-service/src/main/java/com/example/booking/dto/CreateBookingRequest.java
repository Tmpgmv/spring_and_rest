package com.example.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateBookingRequest(
    Long roomId,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    boolean autoSelect,
    @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") @Size(max = 255)
        String requestId) {}
