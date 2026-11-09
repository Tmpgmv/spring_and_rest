package com.example.gateway.filter;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
public class InternalDenyFilter implements GlobalFilter, Ordered {

  private static final List<PathPattern> DENIED_SUBPATHS =
      List.of(
          PathPatternParser.defaultInstance.parse("/api/rooms/*/confirm-availability"),
          PathPatternParser.defaultInstance.parse("/api/rooms/*/release"));

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();

    // 1. Получаем исходный путь.
    String rawPath = request.getPath().pathWithinApplication().value();

    // 2. Декодируем путь для защиты от обхода через %2F.
    String decodedPath;
    try {
      decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      decodedPath = rawPath; // Используем исходный путь при ошибке декодирования.
    }

    for (PathPattern denied : DENIED_SUBPATHS) {
      if (denied.matches(PathContainer.parsePath(decodedPath))) {
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        return exchange.getResponse().setComplete();
      }
    }
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return 0;
  }
}
