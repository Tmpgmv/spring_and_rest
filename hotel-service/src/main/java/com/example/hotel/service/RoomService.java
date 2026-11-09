package com.example.hotel.service;

import com.example.hotel.dto.CreateRoomRequest;
import com.example.hotel.dto.ErrorDTO;
import com.example.hotel.dto.RoomResponse;
import com.example.hotel.entity.Hotel;
import com.example.hotel.entity.Room;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomService {
  private final HotelRepository hotels;
  private final RoomRepository rooms;

  private final Clock clock;

  public RoomService(HotelRepository hotels, RoomRepository rooms, Clock clock) {
    this.hotels = hotels;
    this.rooms = rooms;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<RoomResponse> findFreeRooms(LocalDate startDate, LocalDate endDate, Long hotelId) {
    List<ErrorDTO.FieldError> errors = new ArrayList<>();
    if (startDate.isBefore(LocalDate.now(clock))) {
      errors.add(new ErrorDTO.FieldError("startDate", "must not be in the past"));
    }
    if (!startDate.isBefore(endDate)) {
      errors.add(new ErrorDTO.FieldError("endDate", "must be after startDate"));
    }
    if (!errors.isEmpty()) {
      throw new AvailabilityValidationException(errors);
    }
    return rooms.findFreeRooms(startDate, endDate, hotelId).stream()
        .map(RoomResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<RoomResponse> recommend(LocalDate startDate, LocalDate endDate) {
    return findFreeRooms(startDate, endDate, null).stream()
        .sorted(Comparator.comparingLong(RoomResponse::timesBooked).thenComparing(RoomResponse::id))
        .toList();
  }

  @Transactional
  public RoomResponse create(CreateRoomRequest request) {
    Hotel hotel =
        hotels
            .findById(request.hotelId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel not found"));
    return RoomResponse.from(rooms.save(new Room(hotel, request.number())));
  }
}
