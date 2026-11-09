package com.example.booking.repository;

import com.example.booking.entity.ProcessedRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, Long> {
  @Query(
      "select p.requestId from ProcessedRequest p where p.booking.status = com.example.booking.entity.BookingStatus.PENDING and p.booking.createdAt < :cutoff")
  java.util.List<String> findOrphanRequestIds(@Param("cutoff") java.time.Instant cutoff);

  Optional<ProcessedRequest> findByBookingId(Long bookingId);

  Optional<ProcessedRequest> findByRequestId(String requestId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from ProcessedRequest p where p.requestId = :requestId")
  Optional<ProcessedRequest> findLockedByRequestId(@Param("requestId") String requestId);
}
