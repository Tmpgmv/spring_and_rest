package com.example.hotel.controller;

import com.example.hotel.config.TraceIdFilter;
import com.example.hotel.dto.ErrorDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Единое преобразование исключений в {@link ErrorDTO} для всех конечных точек сервиса. Ошибки
 * валидации возвращают 400 с {@code fieldErrors}; статусы {@link ResponseStatusException}
 * (401/403/404/409/503) сохраняются. Учитываются исключения фреймворка {@link ErrorResponse},
 * включая 404 для неизвестного пути. Остальные ошибки дают 500 с общим сообщением: подробности
 * записываются в журнал и не попадают в тело ответа. Обработчик сначала проверяет ErrorResponse,
 * затем аннотацию класса {@link ResponseStatus}, поэтому доменное исключение с
 * {@code @ResponseStatus(CONFLICT)} не требует отдельного кода. traceId читается из MDC и остаётся
 * пустым при отсутствии; локально он не создаётся. Реализация дублируется в сервисах, которые не
 * разделяют код; различаются имена пакетов.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(com.example.hotel.service.AvailabilityValidationException.class)
  public ResponseEntity<ErrorDTO> handleAvailabilityValidation(
      com.example.hotel.service.AvailabilityValidationException ex, HttpServletRequest request) {
    return build(400, "Validation failed", ex.getFieldErrors(), request);
  }

  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorDTO> handleInvalidParameter(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
      HttpServletRequest request) {
    return build(
        400,
        "Validation failed",
        List.of(new ErrorDTO.FieldError(ex.getName(), "has an invalid value")),
        request);
  }

  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorDTO> handleMissingParameter(
      org.springframework.web.bind.MissingServletRequestParameterException ex,
      HttpServletRequest request) {
    return build(
        400,
        "Validation failed",
        List.of(new ErrorDTO.FieldError(ex.getParameterName(), "is required")),
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorDTO> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ErrorDTO.FieldError> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fieldError ->
                    new ErrorDTO.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
            .toList();
    return build(HttpStatus.BAD_REQUEST.value(), "Validation failed", fieldErrors, request);
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorDTO> handleInvalidJson(HttpServletRequest request) {
    return build(400, "Invalid request body", null, request);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorDTO> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest request) {
    int status = ex.getStatusCode().value();
    String message = ex.getReason() != null ? ex.getReason() : reasonPhrase(status);
    return build(status, message, null, request);
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<ErrorDTO> handleAccessDenied(HttpServletRequest request) {
    return build(403, "Access denied", null, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorDTO> handleUnhandled(Exception ex, HttpServletRequest request) {
    if (ex instanceof ErrorResponse errorResponse) {
      // Исключения фреймворка, например NoResourceFound для неизвестного пути, содержат
      // безопасное описание, которое передаётся в ответ без изменений.
      int status = errorResponse.getStatusCode().value();
      String detail = errorResponse.getBody().getDetail();
      return build(status, detail != null ? detail : reasonPhrase(status), null, request);
    }
    ResponseStatus annotation = AnnotationUtils.findAnnotation(ex.getClass(), ResponseStatus.class);
    if (annotation != null) {
      int status = annotation.code().value();
      String message = annotation.reason().isEmpty() ? reasonPhrase(status) : annotation.reason();
      return build(status, message, null, request);
    }
    LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", null, request);
  }

  /** Общий построитель установленной структуры ErrorDTO для всех обработчиков. */
  private ResponseEntity<ErrorDTO> build(
      int status,
      String message,
      List<ErrorDTO.FieldError> fieldErrors,
      HttpServletRequest request) {
    ErrorDTO body =
        new ErrorDTO(
            Instant.now(),
            status,
            reasonPhrase(status),
            message,
            request.getRequestURI(),
            currentTraceId(),
            fieldErrors);
    return ResponseEntity.status(status).body(body);
  }

  /** traceId из MDC, оставленный фильтром; при отсутствии пустой, локально не создаётся. */
  private String currentTraceId() {
    String traceId = MDC.get(TraceIdFilter.MDC_KEY);
    return traceId == null ? "" : traceId;
  }

  private static String reasonPhrase(int status) {
    HttpStatus resolved = HttpStatus.resolve(status);
    return resolved != null ? resolved.getReasonPhrase() : "Error";
  }
}
