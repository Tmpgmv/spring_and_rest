package com.example.hotel.dto;

import java.time.LocalDate;
import java.util.List;

/** Исторические счётчики и количество слотов HELD с текущей занятостью на asOfDate (UTC). */
public record HotelOccupancyResponse(
    Long hotelId, LocalDate asOfDate, List<RoomOccupancy> rooms, Aggregate aggregate) {
  public record RoomOccupancy(
      Long roomId,
      String number,
      boolean available,
      long timesBooked,
      long heldSlots,
      boolean occupied) {}

  public record Aggregate(long totalRooms, long occupiedRooms, long timesBooked, long heldSlots) {}
}
