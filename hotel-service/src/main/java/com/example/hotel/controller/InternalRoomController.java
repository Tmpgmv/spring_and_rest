package com.example.hotel.controller;

import com.example.hotel.dto.ErrorDTO;
import com.example.hotel.dto.RoomSlotRequest;
import com.example.hotel.service.RoomReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal reservations")
@RestController
@RequestMapping("/api/rooms/{id}")
public class InternalRoomController {
  private final RoomReservationService reservations;

  public InternalRoomController(RoomReservationService reservations) {
    this.reservations = reservations;
  }

  @Operation(
      summary = "Confirm availability idempotently and increment demand once",
      description = "Required role: INTERNAL.",
      security = @SecurityRequirement(name = "internalKey"))
  @ApiResponse(responseCode = "200", description = "Success", content = @Content)
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
  @ApiResponse(
      responseCode = "409",
      description = "Conflict",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping("/confirm-availability")
  @PreAuthorize("hasRole('INTERNAL')")
  public void confirm(@PathVariable Long id, @Valid @RequestBody RoomSlotRequest request) {
    reservations.confirm(id, request);
  }

  @Operation(
      summary = "Release a matching reservation idempotently without decrementing demand",
      description = "Required role: INTERNAL.",
      security = @SecurityRequirement(name = "internalKey"))
  @ApiResponse(responseCode = "200", description = "Success", content = @Content)
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
  @PostMapping("/release")
  @PreAuthorize("hasRole('INTERNAL')")
  public void release(@PathVariable Long id, @Valid @RequestBody RoomSlotRequest request) {
    reservations.release(id, request);
  }
}
