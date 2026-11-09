package com.example.booking.dto;

/**
 * Тело с единственным полем token: регистрация возвращает 201, вход использует ту же структуру с
 * 200.
 */
public record TokenResponse(String token) {}
