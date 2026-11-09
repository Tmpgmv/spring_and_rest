package com.example.hotel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Сводная загрузка гостиниц за полуоткрытый интервал дат, включая исторические периоды. */
public record HotelAnalyticsResponse(
    LocalDate startDate,
    LocalDate endDate,
    long nights,
    AnalyticsTotal total,
    List<HotelAnalytics> hotels) {

  /** Общая вместимость и число забронированных ночей в выбранных гостиницах. */
  public record AnalyticsTotal(
      long availableRoomNights,
      long bookedRoomNights,
      @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal percent) {}
}
