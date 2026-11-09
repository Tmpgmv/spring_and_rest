package com.example.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "JWT_SECRET=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "INTERNAL_API_KEY=production-profile-test-key"
    })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionProfileTest {
  @Autowired MockMvc mvc;
  @Autowired ApplicationContext context;

  /*
   * Исходные данные: профиль prod с явно заданными тестовыми секретами, Eureka отключена;
   * каждый параметр задаёт адрес консоли H2, интерфейса Swagger или документа OpenAPI.
   * Ожидаемый результат: каждый служебный адрес недоступен и возвращает 404.
   */
  @ParameterizedTest(name = "Служебный адрес {0} отключён в prod")
  @ValueSource(
      strings = {
        "/h2-console/",
        "/swagger-ui.html",
        "/swagger-ui/index.html",
        "/v3/api-docs",
        "/v3/api-docs/swagger-config"
      })
  @DisplayName("Профиль эксплуатации отключает консоль базы и HTTP-документацию")
  void diagnosticEndpointsAreDisabled(String path) throws Exception {
    mvc.perform(get(path)).andExpect(status().isNotFound());
  }

  /*
   * Исходные данные: контекст сервиса использует профиль prod с явно заданными тестовыми секретами.
   * Ожидаемый результат: ни одна регистрация сервлета не предоставляет веб-консоль H2.
   */
  @Test
  @DisplayName("Профиль эксплуатации не регистрирует сервлет консоли H2")
  void h2ConsoleServletIsNotRegistered() {
    assertThat(context.getBeansOfType(ServletRegistrationBean.class).values())
        .noneMatch(bean -> bean.getServlet().getClass().getName().startsWith("org.h2."));
  }

  /*
   * Исходные данные: профиль prod с тестовыми секретами; защищённый маршрут вызывается без JWT.
   * Ожидаемый результат: сервис сохраняет собственную проверку подлинности и возвращает 401.
   */
  @Test
  @DisplayName("Профиль эксплуатации сохраняет проверку JWT в самом сервисе")
  void serviceStillRequiresAuthentication() throws Exception {
    mvc.perform(get("/bookings")).andExpect(status().isUnauthorized());
  }
}
