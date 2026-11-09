package com.example.booking.client;

import java.time.LocalDate;

public interface HotelClient {
  java.util.List<RecommendedRoom> recommend(
      LocalDate startDate, LocalDate endDate, String authorization);

  record RecommendedRoom(Long id) {}

  void confirm(Long roomId, LocalDate startDate, LocalDate endDate, String requestId);

  void release(Long roomId, LocalDate startDate, LocalDate endDate, String requestId);
}
