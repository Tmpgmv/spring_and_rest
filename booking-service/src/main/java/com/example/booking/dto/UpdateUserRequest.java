package com.example.booking.dto;

import com.example.booking.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Имя пользователя определяет учётную запись; отсутствующие пароль и роль сохраняют прежние
 * значения.
 */
public record UpdateUserRequest(
    @NotBlank @Size(max = 100) String username,
    @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String password,
    UserRole role) {}
