package com.example.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class OpenApiTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  /*
   * Исходные данные: Анонимный клиент запрашивает Swagger UI, его конфигурацию и /v3/api-docs.
   * Ожидаемый результат: UI перенаправляется и загружается; спецификация содержит шесть путей, bearerAuth,
   * описания ролей и ответов, схемы бронирования и параметры пагинации; вход не требует токена.
   */
  @DisplayName("Swagger и полная типизированная спецификация доступны без авторизации")
  @Test
  void anonymousUsersCanLoadSwaggerAndCompleteTypedSpecification() throws Exception {
    mvc.perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    JsonNode spec =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(spec.path("paths").size()).isEqualTo(6);
    assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText())
        .isEqualTo("bearer");
    spec.path("paths")
        .forEach(
            path ->
                path.forEach(
                    operation -> {
                      assertThat(operation.path("tags").isEmpty()).isFalse();
                      assertThat(operation.path("summary").asText()).isNotBlank();
                      assertThat(operation.path("description").asText()).contains("Required role:");
                      assertThat(operation.path("responses").isEmpty()).isFalse();
                    }));
    assertThat(
            spec.at("/paths/~1booking/post/responses/201/content/application~1json/schema/$ref")
                .asText())
        .isEqualTo("#/components/schemas/BookingResponse");
    assertThat(spec.at("/paths/~1user~1auth/post/security").isMissingNode()).isTrue();
    assertThat(
            spec.at("/paths/~1booking/post/requestBody/content/application~1json/schema/$ref")
                .asText())
        .isEqualTo("#/components/schemas/CreateBookingRequest");
    assertThat(spec.at("/paths/~1bookings/get/parameters").toString())
        .contains("page", "size", "sort");
  }
}
