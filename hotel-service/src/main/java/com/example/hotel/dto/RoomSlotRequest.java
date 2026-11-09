package com.example.hotel.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Общее тело подтверждения и освобождения; прошедшие даты допустимы для повторов и компенсации. */
public record RoomSlotRequest(
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotBlank @Size(max = 255) String requestId) {
  @AssertTrue(message = "endDate must be after startDate")
  public boolean isDateRangeValid() {
    return startDate == null || endDate == null || startDate.isBefore(endDate);
  }
}
