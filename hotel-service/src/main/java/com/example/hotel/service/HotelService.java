package com.example.hotel.service;

import com.example.hotel.dto.CreateHotelRequest;
import com.example.hotel.dto.HotelResponse;
import com.example.hotel.entity.Hotel;
import com.example.hotel.repository.HotelRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotelService {
  private final HotelRepository hotels;

  public HotelService(HotelRepository hotels) {
    this.hotels = hotels;
  }

  @Transactional(readOnly = true)
  public List<HotelResponse> list() {
    return hotels.findAll().stream().map(HotelResponse::from).toList();
  }

  @Transactional
  public HotelResponse create(CreateHotelRequest request) {
    return HotelResponse.from(hotels.save(new Hotel(request.name(), request.address())));
  }
}
