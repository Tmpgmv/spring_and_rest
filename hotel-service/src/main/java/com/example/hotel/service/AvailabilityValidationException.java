package com.example.hotel.service;

import com.example.hotel.dto.ErrorDTO;
import java.util.List;

/** Ошибки валидации параметров доступности номера с привязкой к полям. */
public class AvailabilityValidationException extends RuntimeException {
  private final List<ErrorDTO.FieldError> fieldErrors;

  public AvailabilityValidationException(List<ErrorDTO.FieldError> fieldErrors) {
    super("Validation failed");
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public List<ErrorDTO.FieldError> getFieldErrors() {
    return fieldErrors;
  }
}
