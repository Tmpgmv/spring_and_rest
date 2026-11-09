package com.example.gateway.filter;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Корреляция запросов на шлюзе: каждому запросу назначается {@code X-Trace-Id}. Значение клиента
 * сохраняется без изменений; пустой или отсутствующий заголовок заменяется UUID. Заголовок
 * передаётся далее, чтобы сервисы использовали один ключ MDC {@code traceId}. Фильтр имеет
 * приоритет {@link Ordered#HIGHEST_PRECEDENCE} и выполняется до запрета внутренних операций
 * (порядок 0) и маршрутизации, поэтому отклонённые запросы тоже отслеживаются. MDC устанавливается
 * и очищается синхронно вокруг единственной записи INFO в вызывающем потоке. Контекст не
 * переносится через {@code chain.filter(...)}, исключая утечки в потоки Netty.
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

  public static final String TRACE_HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";

  private static final Logger LOG = LoggerFactory.getLogger(TraceIdFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();

    // Первое непустое значение клиента имеет приоритет и больше не заменяется.
    String traceId = null;
    List<String> supplied = request.getHeaders().get(TRACE_HEADER);
    if (supplied != null) {
      for (String value : supplied) {
        if (!value.isBlank()) {
          traceId = value;
          break;
        }
      }
    }
    String resolvedTraceId = traceId != null ? traceId : UUID.randomUUID().toString();

    // set() вместо add() сводит многозначный заголовок к одному используемому значению.
    ServerHttpRequest mutated =
        request.mutate().headers(headers -> headers.set(TRACE_HEADER, resolvedTraceId)).build();

    MDC.put(MDC_KEY, resolvedTraceId);
    try {
      LOG.info("{} {}", request.getMethod(), request.getURI().getRawPath());
    } finally {
      MDC.remove(MDC_KEY);
    }

    return chain.filter(exchange.mutate().request(mutated).build());
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
