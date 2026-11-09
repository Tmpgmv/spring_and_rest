package com.example.hotel.controller;

import com.example.hotel.dto.CreateHotelRequest;
import com.example.hotel.dto.ErrorDTO;
import com.example.hotel.dto.HotelOccupancyResponse;
import com.example.hotel.dto.HotelResponse;
import com.example.hotel.service.HotelService;
import com.example.hotel.service.OccupancyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Hotels")
@RestController
@RequestMapping("/api/hotels")
public class HotelController {
  private final HotelService hotels;

  private final OccupancyService occupancy;

  public HotelController(HotelService hotels, OccupancyService occupancy) {
    this.hotels = hotels;
    this.occupancy = occupancy;
  }

  @Operation(
      summary = "List hotels",
      description = "Required role: USER.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true)
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
  public ResponseEntity<List<HotelResponse>> list() {
    return ResponseEntity.ok(hotels.list());
  }

  @Operation(
      summary = "Read hotel occupancy in UTC",
      description = "Required role: ADMIN.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true)
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
  @GetMapping("/{hotelId}/occupancy")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<HotelOccupancyResponse> occupancy(@PathVariable Long hotelId) {
    return ResponseEntity.ok(occupancy.getOccupancy(hotelId));
  }

  @Operation(
      summary = "Create a hotel",
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
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<HotelResponse> create(@Valid @RequestBody CreateHotelRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(hotels.create(request));
  }
}
