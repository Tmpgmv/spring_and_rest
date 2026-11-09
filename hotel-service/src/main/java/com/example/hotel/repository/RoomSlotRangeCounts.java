package com.example.hotel.repository;

/** Суммарное пересечение удержанных слотов номера с выбранным интервалом. */
public interface RoomSlotRangeCounts {
  Long getRoomId();

  Long getBookedNights();
}
