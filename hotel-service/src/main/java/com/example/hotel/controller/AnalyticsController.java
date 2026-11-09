package com.example.hotel.controller;

import com.example.hotel.dto.HotelAnalyticsResponse;
import com.example.hotel.service.HotelAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Отчёт о загрузке гостиниц за выбранный период. */
@Tag(name = "Hotels")
@RestController
@RequestMapping("/api/hotels")
public class AnalyticsController {
  private final HotelAnalyticsService analytics;

  public AnalyticsController(HotelAnalyticsService analytics) {
    this.analytics = analytics;
  }

  @Operation(
      summary = "Read hotel analytics for a date range",
      description = "Required role: ADMIN. Historical date ranges are supported.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @GetMapping("/analytics")
  @PreAuthorize("hasRole('ADMIN')")
  public HotelAnalyticsResponse analytics(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false) Long hotelId) {
    return analytics.getAnalytics(startDate, endDate, hotelId);
  }
}
