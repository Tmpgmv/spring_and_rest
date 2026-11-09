package com.example.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class HotelClientConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @LoadBalanced
  RestClient.Builder hotelRestClientBuilder(
      @Value("${hotel.client.connect-timeout}") Duration connectTimeout,
      @Value("${hotel.client.read-timeout}") Duration readTimeout,
      ObjectMapper mapper) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeout);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder()
        .requestFactory(factory)
        .messageConverters(
            converters -> {
              for (var converter : converters) {
                if (converter instanceof MappingJackson2HttpMessageConverter jackson)
                  jackson.setObjectMapper(mapper);
              }
            });
  }
}
