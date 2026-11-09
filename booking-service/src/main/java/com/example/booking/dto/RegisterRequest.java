package com.example.booking.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Тело регистрации: два обязательных поля учётных данных. Нарушения {@code @NotBlank}
 * обрабатываются GlobalExceptionHandler как 400 с fieldErrors. Электронная почта и токен обновления
 * не предусмотрены.
 */
public record RegisterRequest(@NotBlank String username, @NotBlank String password) {}
