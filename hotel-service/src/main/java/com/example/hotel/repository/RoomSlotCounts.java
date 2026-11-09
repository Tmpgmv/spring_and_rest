package com.example.hotel.repository;

/** Сгруппированная статистика слотов; занятые слоты покрывают запрошенную отчётную дату. */
public interface RoomSlotCounts {
  Long getRoomId();

  long getHeldSlots();

  long getOccupiedSlots();
}
