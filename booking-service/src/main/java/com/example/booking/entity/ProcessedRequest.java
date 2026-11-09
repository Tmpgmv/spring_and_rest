package com.example.booking.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "processed_requests",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_processed_requests_request_id", columnNames = "request_id"))
public class ProcessedRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id", nullable = false, length = 255)
  private String requestId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "booking_id", nullable = false, unique = true)
  private Booking booking;

  @Column(name = "http_status")
  private Integer httpStatus;

  @Lob
  @Column(name = "response_snapshot")
  private String responseSnapshot;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected ProcessedRequest() {}

  public ProcessedRequest(String requestId, Booking booking, Instant processedAt) {
    this.requestId = requestId;
    this.booking = booking;
    this.processedAt = processedAt;
  }

  public String getRequestId() {
    return requestId;
  }

  public Booking getBooking() {
    return booking;
  }

  public Integer getHttpStatus() {
    return httpStatus;
  }

  public String getResponseSnapshot() {
    return responseSnapshot;
  }

  public void complete(int status, String body, Instant now) {
    httpStatus = status;
    responseSnapshot = body;
    processedAt = now;
  }
}
