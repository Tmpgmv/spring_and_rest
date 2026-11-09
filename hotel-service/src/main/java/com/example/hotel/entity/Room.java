package com.example.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "rooms")
public class Room {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hotel_id", nullable = false)
  private Hotel hotel;

  @Column(nullable = false)
  private String number;

  /** Эксплуатационная доступность; занятость по датам учитывается слотами номера. */
  @Column(nullable = false)
  private boolean available = true;

  @Column(name = "times_booked", nullable = false)
  private long timesBooked = 0;

  protected Room() {
    // Конструктор для JPA.
  }

  public Room(Hotel hotel, String number) {
    this.hotel = hotel;
    this.number = number;
  }

  public void incrementTimesBooked() {
    timesBooked++;
  }

  public void setAvailable(boolean available) {
    this.available = available;
  }

  public Long getId() {
    return id;
  }

  public Hotel getHotel() {
    return hotel;
  }

  public String getNumber() {
    return number;
  }

  public boolean isAvailable() {
    return available;
  }

  public long getTimesBooked() {
    return timesBooked;
  }
}
