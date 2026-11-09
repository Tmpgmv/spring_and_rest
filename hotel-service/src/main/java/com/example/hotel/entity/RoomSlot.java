package com.example.hotel.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "room_slots",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_room_slots_request_id", columnNames = "request_id"))
public class RoomSlot {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RoomSlotStatus status = RoomSlotStatus.HELD;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected RoomSlot() {
    // Конструктор для JPA.
  }

  public RoomSlot(
      Room room, LocalDate startDate, LocalDate endDate, String requestId, Instant createdAt) {
    this.room = room;
    this.startDate = startDate;
    this.endDate = endDate;
    this.requestId = requestId;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public Room getRoom() {
    return room;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public String getRequestId() {
    return requestId;
  }

  public RoomSlotStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
