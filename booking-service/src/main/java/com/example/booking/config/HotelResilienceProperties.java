package com.example.booking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hotel.client.resilience")
public record HotelResilienceProperties(
    Duration timeout,
    int maxAttempts,
    Duration initialBackoff,
    double backoffMultiplier,
    float failureRateThreshold,
    int windowSize,
    int minimumCalls,
    Duration openDuration,
    int halfOpenCalls,
    int workers) {
  public HotelResilienceProperties {
    if (timeout == null
        || timeout.isZero()
        || timeout.isNegative()
        || initialBackoff == null
        || initialBackoff.toMillis() < 1
        || openDuration == null
        || openDuration.isZero()
        || openDuration.isNegative()
        || maxAttempts < 1
        || !Double.isFinite(backoffMultiplier)
        || backoffMultiplier < 1
        || !(failureRateThreshold > 0 && failureRateThreshold <= 100)
        || windowSize < 1
        || minimumCalls < 1
        || minimumCalls > windowSize
        || halfOpenCalls < 1
        || workers < 1) {
      throw new IllegalArgumentException("Invalid hotel.client.resilience settings");
    }
  }
}
