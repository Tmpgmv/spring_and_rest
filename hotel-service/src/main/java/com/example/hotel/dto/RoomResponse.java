package com.example.hotel.dto;

import com.example.hotel.entity.Room;

public record RoomResponse(
    Long id, Long hotelId, String number, boolean available, long timesBooked) {
  public static RoomResponse from(Room room) {
    return new RoomResponse(
        room.getId(),
        room.getHotel().getId(),
        room.getNumber(),
        room.isAvailable(),
        room.getTimesBooked());
  }
}
