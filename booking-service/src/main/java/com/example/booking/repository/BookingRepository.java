package com.example.booking.repository;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Booking b where b.id = :id and b.user.username = :username")
  Optional<Booking> findOwnedForCancellation(
      @Param("id") Long id, @Param("username") String username);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Booking b set b.status = com.example.booking.entity.BookingStatus.CANCELLED where b.id = :id and b.status = :expected")
  int cancel(@Param("id") Long id, @Param("expected") BookingStatus expected);

  Optional<Booking> findByIdAndUserUsername(Long id, String username);

  Page<Booking> findByUserUsername(String username, Pageable pageable);

  @Modifying(flushAutomatically = true)
  @Query(
      "update Booking b set b.status = :status where b.id = :id and b.status = com.example.booking.entity.BookingStatus.PENDING")
  int completePending(@Param("id") Long id, @Param("status") BookingStatus status);
}
