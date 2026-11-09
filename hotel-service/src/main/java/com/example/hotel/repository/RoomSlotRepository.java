package com.example.hotel.repository;

import com.example.hotel.entity.RoomSlot;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomSlotRepository extends JpaRepository<RoomSlot, Long> {
  java.util.Optional<RoomSlot> findByRequestId(String requestId);

  @Query(
      """
            select (count(s) > 0) from RoomSlot s
            where s.room.id = :roomId and s.status = com.example.hotel.entity.RoomSlotStatus.HELD
              and s.startDate < :endDate and s.endDate > :startDate
            """)
  boolean hasOverlap(
      @Param("roomId") Long roomId,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate);

  @Query(
      """
            select s.room.id as roomId, count(s) as heldSlots,
                   sum(case when s.startDate <= :date and s.endDate > :date then 1 else 0 end) as occupiedSlots
            from RoomSlot s
            where s.room.hotel.id = :hotelId and s.status = com.example.hotel.entity.RoomSlotStatus.HELD
            group by s.room.id
            """)
  List<RoomSlotCounts> countByRoomForHotel(
      @Param("hotelId") Long hotelId, @Param("date") LocalDate date);

  /** Суммирует пересечения слотов с периодом: отрицательные длительности заменяются нулём. */
  @Query(
      value =
          """
            select s.room_id as roomId,
                   sum(greatest(0, datediff('DAY', greatest(s.start_date, :startDate),
                                           least(s.end_date, :endDate)))) as bookedNights
            from room_slots s join rooms r on r.id = s.room_id
            where s.status = 'HELD' and (:hotelId is null or r.hotel_id = :hotelId)
            group by s.room_id
            """,
      nativeQuery = true)
  List<RoomSlotRangeCounts> countBookedNightsInRange(
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate,
      @Param("hotelId") Long hotelId);
}
