package com.example.booking.logging;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.MDC;

/** Ограниченный контекст корреляции: MDC вызывающего кода восстанавливается даже при ошибке. */
public final class SagaLog implements AutoCloseable {
  private final Map<String, String> previous = MDC.getCopyOfContextMap();

  public SagaLog(String bookingId, String requestId) {
    MDC.put("bookingId", bookingId == null ? "" : bookingId);
    MDC.put("requestId", requestId);
    MDC.put("traceId", MDC.get("traceId") == null ? "" : MDC.get("traceId"));
  }

  public static void step(Logger logger, String step, String outcome) {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    try {
      MDC.put("step", step);
      MDC.put("outcome", outcome);
      String message = "bookingId={} requestId={} traceId={} step={} outcome={}";
      Object[] values = {
        MDC.get("bookingId"), MDC.get("requestId"), MDC.get("traceId"), step, outcome
      };
      if (outcome.equals("FAILED")) logger.error(message, values);
      else logger.info(message, values);
    } finally {
      restore(previous);
    }
  }

  private static void restore(Map<String, String> context) {
    if (context == null) MDC.clear();
    else MDC.setContextMap(context);
  }

  @Override
  public void close() {
    restore(previous);
  }
}
