package com.example.hotel.service;

import com.example.hotel.dto.ErrorDTO;
import com.example.hotel.dto.HotelAnalytics;
import com.example.hotel.dto.HotelAnalyticsResponse;
import com.example.hotel.dto.HotelAnalyticsResponse.AnalyticsTotal;
import com.example.hotel.dto.RoomAnalytics;
import com.example.hotel.entity.Room;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotRangeCounts;
import com.example.hotel.repository.RoomSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Рассчитывает загрузку гостиниц за произвольный полуоткрытый интервал дат. */
@Service
public class HotelAnalyticsService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final RoomSlotRepository slots;

  public HotelAnalyticsService(
      HotelRepository hotels, RoomRepository rooms, RoomSlotRepository slots) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.slots = slots;
  }

  /** Исторические даты допустимы; конец периода должен быть строго позже начала. */
  @Transactional(readOnly = true)
  public HotelAnalyticsResponse getAnalytics(LocalDate startDate, LocalDate endDate, Long hotelId) {
    if (!startDate.isBefore(endDate)) {
      throw new AvailabilityValidationException(
          List.of(new ErrorDTO.FieldError("endDate", "must be after startDate")));
    }
    var scope = findHotels(hotelId);
    long nights = ChronoUnit.DAYS.between(startDate, endDate);
    Map<Long, Long> bookedByRoom =
        slots.countBookedNightsInRange(startDate, endDate, hotelId).stream()
            .collect(
                Collectors.toMap(
                    RoomSlotRangeCounts::getRoomId, RoomSlotRangeCounts::getBookedNights));
    Map<Long, List<Room>> roomsByHotel =
        rooms.findAnalyticsRooms(hotelId).stream()
            .collect(Collectors.groupingBy(room -> room.getHotel().getId()));
    List<HotelAnalytics> statistics =
        scope.stream()
            .map(
                hotel -> {
                  List<Room> hotelRooms = roomsByHotel.getOrDefault(hotel.getId(), List.of());
                  long available = hotelRooms.stream().filter(Room::isAvailable).count() * nights;
                  List<RoomAnalytics> roomStatistics =
                      hotelRooms.stream()
                          .map(
                              room -> {
                                long booked = bookedByRoom.getOrDefault(room.getId(), 0L);
                                return new RoomAnalytics(
                                    room.getId(),
                                    room.getNumber(),
                                    booked,
                                    percent(booked, room.isAvailable() ? nights : 0));
                              })
                          .toList();
                  long booked =
                      roomStatistics.stream().mapToLong(RoomAnalytics::bookedRoomNights).sum();
                  return new HotelAnalytics(
                      hotel.getId(),
                      hotel.getName(),
                      available,
                      booked,
                      percent(booked, available),
                      roomStatistics);
                })
            .toList();
    long available = statistics.stream().mapToLong(HotelAnalytics::availableRoomNights).sum();
    long booked = statistics.stream().mapToLong(HotelAnalytics::bookedRoomNights).sum();
    return new HotelAnalyticsResponse(
        startDate,
        endDate,
        nights,
        new AnalyticsTotal(available, booked, percent(booked, available)),
        statistics);
  }

  private List<com.example.hotel.entity.Hotel> findHotels(Long hotelId) {
    return hotelId == null
        ? hotels.findAll(Sort.by("id"))
        : List.of(
            hotels
                .findById(hotelId)
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found")));
  }

  private static BigDecimal percent(long booked, long available) {
    return available > 0
        ? BigDecimal.valueOf(booked)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(available), 1, RoundingMode.HALF_UP)
        : null;
  }
}
