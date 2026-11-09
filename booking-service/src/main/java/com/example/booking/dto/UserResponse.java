package com.example.booking.dto;

import com.example.booking.entity.User;
import com.example.booking.entity.UserRole;

/** Публичное представление учётной записи; учётные данные не сериализуются. */
public record UserResponse(Long id, String username, UserRole role) {
  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getUsername(), user.getRole());
  }
}
