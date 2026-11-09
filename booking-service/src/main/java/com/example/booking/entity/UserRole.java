package com.example.booking.entity;

/**
 * Роль зарегистрированного пользователя: поле role токена содержит USER или ADMIN. В таблице users
 * хранится строковое имя перечисления; регистрация всегда назначает {@link #USER}.
 */
public enum UserRole {
  USER,
  ADMIN
}
