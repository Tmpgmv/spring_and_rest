package com.example.booking.controller;

import com.example.booking.dto.BookingHistoryResponse;
import com.example.booking.dto.BookingResponse;
import com.example.booking.dto.CreateBookingRequest;
import com.example.booking.dto.ErrorDTO;
import com.example.booking.saga.BookingSaga;
import com.example.booking.service.BookingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Bookings")
@RestController
public class BookingController {
  private final com.example.booking.service.BookingCancellationService cancellation;
  private final BookingSaga saga;
  private final BookingQueryService queries;

  public BookingController(
      BookingSaga saga,
      BookingQueryService queries,
      com.example.booking.service.BookingCancellationService cancellation) {
    this.cancellation = cancellation;
    this.saga = saga;
    this.queries = queries;
  }

  @Operation(
      summary = "List own booking history",
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
  @GetMapping("/bookings")
  @PreAuthorize("hasRole('USER')")
  public Page<BookingHistoryResponse> list(
      @ParameterObject Pageable pageable, Authentication authentication) {
    return queries.list(authentication.getName(), pageable);
  }

  @Operation(
      summary = "Read own booking; another user’s booking returns 404",
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
  @ApiResponse(
      responseCode = "404",
      description = "Resource not found",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @GetMapping("/booking/{id}")
  @PreAuthorize("hasRole('USER')")
  public BookingHistoryResponse get(@PathVariable Long id, Authentication authentication) {
    return queries.get(id, authentication.getName());
  }

  @Operation(
      summary = "Cancel own booking idempotently and release its slot",
      description = "Required role: USER.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(responseCode = "204", description = "Success", content = @Content)
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
  @DeleteMapping("/booking/{id}")
  @PreAuthorize("hasRole('USER')")
  public ResponseEntity<Void> cancel(@PathVariable Long id, Authentication authentication) {
    cancellation.cancel(id, authentication.getName());
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Create a booking; replay the saved outcome for the same requestId",
      description = "Required role: USER.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @ApiResponse(
      responseCode = "201",
      description = "Success",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = BookingResponse.class)))
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
  @ApiResponse(
      responseCode = "503",
      description =
          "Hotel service unavailable or booking still processing; retry with the same requestId",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = ErrorDTO.class)))
  @PostMapping("/booking")
  @PreAuthorize("hasRole('USER')")
  public ResponseEntity<String> create(
      @Valid @RequestBody CreateBookingRequest request,
      Authentication authentication,
      @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader("Authorization")
          String authorization) {
    var outcome = saga.create(authentication.getName(), request, authorization);
    return ResponseEntity.status(outcome.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(outcome.body());
  }
}
