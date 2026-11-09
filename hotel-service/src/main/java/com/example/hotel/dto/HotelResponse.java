package com.example.hotel.dto;

import com.example.hotel.entity.Hotel;

public record HotelResponse(Long id, String name, String address) {
  public static HotelResponse from(Hotel hotel) {
    return new HotelResponse(hotel.getId(), hotel.getName(), hotel.getAddress());
  }
}
