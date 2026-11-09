package com.example.hotel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** Загрузка гостиницы с детализацией по всем номерам, включая номера без бронирований. */
public record HotelAnalytics(
    Long hotelId,
    String name,
    long availableRoomNights,
    long bookedRoomNights,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal percent,
    List<RoomAnalytics> rooms) {}
