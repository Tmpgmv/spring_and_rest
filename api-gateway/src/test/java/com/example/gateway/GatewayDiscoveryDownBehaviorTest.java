package com.example.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * При недоступной Eureka шлюз должен запускаться, а запрос по маршруту — завершаться ошибкой 5xx
 * балансировщика, без зависания или потери ошибки. Здесь клиент Eureka полностью отключён: это
 * более слабый отказ, чем недоступность сервера. Адреса маршрутов не заменяются: используются
 * реальные логические имена lb:// из application.yml, проверяя разрешение балансировщиком при
 * отсутствии доступных экземпляров.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "eureka.client.enabled=false")
class GatewayDiscoveryDownBehaviorTest {

  @Autowired private WebTestClient client;

  /*
   * Исходные данные: Клиент Eureka отключён, экземпляры сервиса отсутствуют; отправляется GET /api/hotels.
   * Ожидаемый результат: Шлюз возвращает HTTP-статус из диапазона 5xx.
   */
  @Test
  @DisplayName("Запрос к отелям без доступных экземпляров сервиса возвращает ошибку 5xx")
  void routedRequestWithoutAvailableInstancesFailsWith5xx() {
    client.get().uri("/api/hotels").exchange().expectStatus().is5xxServerError();
  }
}
