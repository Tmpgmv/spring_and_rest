package com.example.hotel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/** Число забронированных ночей; процент отсутствует при нулевой доступной вместимости номера. */
public record RoomAnalytics(
    Long roomId,
    String number,
    long bookedRoomNights,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal percent) {}
