package com.example.hotel.repository;

import com.example.hotel.entity.Room;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id = :id")
  java.util.Optional<Room> findLockedById(@Param("id") Long id);

  List<Room> findByHotelIdOrderByIdAsc(Long hotelId);

  @Query(
      "select r from Room r join fetch r.hotel where (:hotelId is null or r.hotel.id = :hotelId) order by r.id")
  List<Room> findAnalyticsRooms(@Param("hotelId") Long hotelId);

  @Query(
      """
            select r from Room r join fetch r.hotel
            where r.available = true
              and (:hotelId is null or r.hotel.id = :hotelId)
              and not exists (
                  select s.id from RoomSlot s
                  where s.room = r and s.status = com.example.hotel.entity.RoomSlotStatus.HELD
                    and s.startDate < :endDate and s.endDate > :startDate
              )
            """)
  List<Room> findFreeRooms(
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("hotelId") Long hotelId);
}
