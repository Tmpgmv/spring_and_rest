package com.example.booking.controller;

import com.example.booking.dto.CreateUserRequest;
import com.example.booking.dto.ErrorDTO;
import com.example.booking.dto.RegisterRequest;
import com.example.booking.dto.TokenResponse;
import com.example.booking.dto.UpdateUserRequest;
import com.example.booking.dto.UserResponse;
import com.example.booking.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публичные регистрация и вход, а также управление учётными записями только для ADMIN по пути
 * /user.
 */
@Tag(name = "Users")
@RestController
@RequestMapping("/user")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @Operation(
      summary = "Create a user account",
      description = "Required role: ADMIN.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "201", description = "Success", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Required role or internal key missing",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Conflict",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
  }

  @Operation(
      summary = "Update a user account",
      description = "Required role: ADMIN.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Required role or internal key missing",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Resource not found",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PatchMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<UserResponse> update(@Valid @RequestBody UpdateUserRequest request) {
    return ResponseEntity.ok(userService.update(request));
  }

  @Operation(
      summary = "Delete a user account",
      description = "Required role: ADMIN.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "204", description = "Success", content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Required role or internal key missing",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Resource not found",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @DeleteMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@RequestParam String username) {
    userService.delete(username);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Register a USER account and issue a JWT",
      description = "Required role: PUBLIC.")
  @ApiResponse(responseCode = "201", description = "Success", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Conflict",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping("/register")
  public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
  }

  @Operation(summary = "Authenticate and issue a JWT", description = "Required role: PUBLIC.")
  @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping("/auth")
  public ResponseEntity<TokenResponse> authenticate(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.ok(userService.authenticate(request));
  }
}
