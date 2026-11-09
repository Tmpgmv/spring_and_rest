package com.example.booking.client;

import com.example.booking.config.HotelResilienceProperties;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.timelimiter.*;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Повтор → отдельный автоматический выключатель операции → срок каждой попытки → HTTP-транспорт.
 */
@Component
@Primary
@EnableConfigurationProperties(HotelResilienceProperties.class)
public class ResilientHotelClient implements HotelClient, AutoCloseable {
  private final HotelClient delegate;
  private final Retry retry;
  private final TimeLimiter limiter;
  private final CircuitBreakerRegistry breakers;
  private final ExecutorService workers;

  public ResilientHotelClient(RestHotelClient delegate, HotelResilienceProperties settings) {
    this.delegate = delegate;
    retry =
        Retry.of(
            "hotel",
            RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(
                        settings.initialBackoff(), settings.backoffMultiplier()))
                .retryOnException(ResilientHotelClient::transientFailure)
                .build());
    limiter =
        TimeLimiter.of(
            TimeLimiterConfig.custom()
                .timeoutDuration(settings.timeout())
                .cancelRunningFuture(true)
                .build());
    breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .ignoreException(ex -> ex instanceof HotelCallException && !transientFailure(ex))
                .build());
    // Очередь ограничена: транспортные вызовы с истёкшим сроком не могут бесконечно накапливать
    // фоновую работу.
    workers =
        new ThreadPoolExecutor(
            settings.workers(),
            settings.workers(),
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  private static boolean transientFailure(Throwable ex) {
    return ex instanceof HotelCallException failure
        && failure.getStatus() >= 500
        && failure.getStatus() < 600;
  }

  private <T> T execute(String operation, Supplier<T> action) {
    try {
      return retry.executeSupplier(
          () ->
              breakers
                  .circuitBreaker(operation)
                  .executeSupplier(
                      () -> {
                        Map<String, String> context = MDC.getCopyOfContextMap();
                        try {
                          return limiter.executeFutureSupplier(
                              () ->
                                  workers.submit(
                                      () -> {
                                        try {
                                          if (context != null) MDC.setContextMap(context);
                                          return action.get();
                                        } finally {
                                          MDC.clear();
                                        }
                                      }));
                        } catch (TimeoutException ex) {
                          throw new HotelCallException(503, ex);
                        } catch (InterruptedException ex) {
                          Thread.currentThread().interrupt();
                          throw new HotelCallException(0, ex);
                        } catch (RuntimeException ex) {
                          throw ex;
                        } catch (Exception ex) {
                          throw new HotelCallException(0, ex);
                        }
                      }));
    } catch (CallNotPermittedException | RejectedExecutionException ex) {
      // Не повторяем вызов при разомкнутой цепи или заполненном пуле потоков.
      throw new HotelCallException(503, ex);
    }
  }

  @Override
  public List<RecommendedRoom> recommend(LocalDate start, LocalDate end, String authorization) {
    return execute("recommend", () -> delegate.recommend(start, end, authorization));
  }

  @Override
  public void confirm(Long roomId, LocalDate start, LocalDate end, String requestId) {
    execute(
        "confirm",
        () -> {
          delegate.confirm(roomId, start, end, requestId);
          return null;
        });
  }

  @Override
  public void release(Long roomId, LocalDate start, LocalDate end, String requestId) {
    execute(
        "release",
        () -> {
          delegate.release(roomId, start, end, requestId);
          return null;
        });
  }

  @PreDestroy
  @Override
  public void close() {
    workers.shutdownNow();
  }
}
