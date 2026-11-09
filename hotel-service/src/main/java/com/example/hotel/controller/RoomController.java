package com.example.hotel.controller;

import com.example.hotel.dto.CreateRoomRequest;
import com.example.hotel.dto.ErrorDTO;
import com.example.hotel.dto.RoomResponse;
import com.example.hotel.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Rooms")
@RestController
@RequestMapping("/api/rooms")
public class RoomController {
  private final RoomService rooms;

  public RoomController(RoomService rooms) {
    this.rooms = rooms;
  }

  @Operation(
      summary = "Find available rooms for a date interval",
      description = "Required role: USER.",
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
  @GetMapping
  @PreAuthorize("hasRole('USER')")
  public ResponseEntity<List<RoomResponse>> findFreeRooms(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
      @RequestParam(required = false) Long hotelId) {
    return ResponseEntity.ok(rooms.findFreeRooms(startDate, endDate, hotelId));
  }

  @Operation(
      summary = "Recommend available rooms ordered by timesBooked then ID",
      description = "Required role: USER.",
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
  @GetMapping("/recommend")
  @PreAuthorize("hasRole('USER')")
  public ResponseEntity<List<RoomResponse>> recommend(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
    return ResponseEntity.ok(rooms.recommend(startDate, endDate));
  }

  @Operation(
      summary = "Create a room",
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
      responseCode = "404",
      description = "Resource not found",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RoomResponse> create(@Valid @RequestBody CreateRoomRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(rooms.create(request));
  }
}
