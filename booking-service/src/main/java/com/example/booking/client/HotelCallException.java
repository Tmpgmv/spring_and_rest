package com.example.booking.client;

public class HotelCallException extends RuntimeException {
  private final int status;

  public HotelCallException(int status, Throwable cause) {
    super("Hotel operation failed", cause);
    this.status = status;
  }

  public int getStatus() {
    return status;
  }
}
