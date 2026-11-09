package com.example.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.server.enable-self-preservation=false",
      "eureka.instance.hostname=localhost"
    })
class EurekaServerApplicationTest {
  @Autowired TestRestTemplate http;
  @Autowired EurekaClientConfigBean clientConfig;

  /*
   * Исходные данные: Сервер Eureka запущен на случайном порту; выполняется GET /eureka/apps.
   * Ожидаемый результат: Регистрация и загрузка внешнего реестра отключены; ответ имеет статус 200 и массив
   * applications.application.
   */
  @Test
  @DisplayName("Автономный сервер Eureka публикует реестр без регистрации у другого сервера")
  void standaloneServerPublishesRegistryWithoutRequiringAnotherEurekaServer() throws Exception {
    assertThat(clientConfig.shouldRegisterWithEureka()).isFalse();
    assertThat(clientConfig.shouldFetchRegistry()).isFalse();
    var response = http.getForEntity("/eureka/apps", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("applications").path("application").isArray()).isTrue();
  }

  /*
   * Исходные данные: В реестре отсутствует UNREGISTERED-SERVICE; выполняется GET /eureka/apps/UNREGISTERED-
   * SERVICE.
   * Ожидаемый результат: Ответ имеет статус 404.
   */
  @Test
  @DisplayName("Поиск незарегистрированного приложения в Eureka возвращает 404")
  void unknownApplicationReturns404() throws Exception {
    assertThat(http.getForEntity("/eureka/apps/UNREGISTERED-SERVICE", String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
