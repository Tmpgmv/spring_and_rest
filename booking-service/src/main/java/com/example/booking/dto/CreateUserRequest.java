package com.example.booking.dto;

import com.example.booking.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 100) String username,
    @NotBlank String password,
    @NotNull UserRole role) {}
