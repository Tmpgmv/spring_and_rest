package com.example.hotel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Единый формат JSON для ошибок сервиса: время, HTTP-статус, его название, сообщение, URI запроса,
 * traceId от шлюза и необязательные ошибки валидации полей. traceId берётся из MDC, установленного
 * TraceIdFilter; при прямом вызове в обход шлюза остаётся пустым и локально не создаётся. При
 * отсутствии fieldErrors поле исключается из JSON. Реализация дублируется в сервисах, поскольку
 * модули не разделяют код; различаются имена пакетов.
 */
public record ErrorDTO(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    String traceId,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> fieldErrors) {

  /** Описание отклонённого поля при валидации: имя и стандартное сообщение валидатора. */
  public record FieldError(String field, String message) {}
}
