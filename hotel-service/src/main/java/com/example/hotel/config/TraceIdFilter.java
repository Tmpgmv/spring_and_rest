package com.example.hotel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Корреляция на целевом сервисе: заголовок шлюза {@code X-Trace-Id} читается в MDC {@code traceId}
 * на время запроса. На запрос создаётся одна запись INFO, а в {@code finally} MDC очищается, чтобы
 * контекст не попал в следующий запрос рабочего потока. Фильтр только читает заголовок: при прямом
 * вызове в обход шлюза traceId остаётся пустым ({@code %X{traceId:-}}), локальное значение не
 * создаётся.
 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String TRACE_HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";

  private static final Logger LOG = LoggerFactory.getLogger(TraceIdFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = request.getHeader(TRACE_HEADER);
    MDC.put(MDC_KEY, traceId == null ? "" : traceId);
    String previousBookingId = MDC.get("bookingId");
    MDC.put(
        "bookingId",
        request.getHeader("X-Booking-Id") == null ? "" : request.getHeader("X-Booking-Id"));
    try {
      LOG.info("{} {}", request.getMethod(), request.getRequestURI());
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      if (previousBookingId == null) MDC.remove("bookingId");
      else MDC.put("bookingId", previousBookingId);
    }
  }
}
