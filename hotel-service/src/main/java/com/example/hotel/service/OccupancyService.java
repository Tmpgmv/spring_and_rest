package com.example.hotel.service;

import com.example.hotel.dto.HotelOccupancyResponse;
import com.example.hotel.dto.HotelOccupancyResponse.Aggregate;
import com.example.hotel.dto.HotelOccupancyResponse.RoomOccupancy;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotCounts;
import com.example.hotel.repository.RoomSlotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OccupancyService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;
  private final RoomSlotRepository slots;
  private final Clock clock;

  public OccupancyService(
      HotelRepository hotels, RoomRepository rooms, RoomSlotRepository slots, Clock clock) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.slots = slots;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public HotelOccupancyResponse getOccupancy(Long hotelId) {
    if (!hotels.existsById(hotelId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found");
    }
    LocalDate today = LocalDate.now(clock);
    var counts =
        slots.countByRoomForHotel(hotelId, today).stream()
            .collect(Collectors.toMap(RoomSlotCounts::getRoomId, Function.identity()));
    var statistics =
        rooms.findByHotelIdOrderByIdAsc(hotelId).stream()
            .map(
                room -> {
                  RoomSlotCounts count = counts.get(room.getId());
                  return new RoomOccupancy(
                      room.getId(),
                      room.getNumber(),
                      room.isAvailable(),
                      room.getTimesBooked(),
                      count == null ? 0 : count.getHeldSlots(),
                      count != null && count.getOccupiedSlots() > 0);
                })
            .toList();
    var aggregate =
        new Aggregate(
            statistics.size(),
            statistics.stream().filter(RoomOccupancy::occupied).count(),
            statistics.stream().mapToLong(RoomOccupancy::timesBooked).sum(),
            statistics.stream().mapToLong(RoomOccupancy::heldSlots).sum());
    return new HotelOccupancyResponse(hotelId, today, statistics, aggregate);
  }
}
