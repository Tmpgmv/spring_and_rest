package com.example.hotel.config;

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
   * Исходные данные: Контекст сервиса запущен без Eureka; клиент без токена запрашивает Swagger UI, его
   * конфигурацию и /v3/api-docs.
   * Ожидаемый результат: UI перенаправляет на index.html, затем ресурсы доступны с 200; спецификация
   * содержит ровно семь ожидаемых путей, схемы bearer и внутреннего ключа, описания ролей и ответов и
   * ссылку CreateRoomRequest.
   */
  @DisplayName("Анонимный клиент получает Swagger и полную типизированную спецификацию")
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
    assertThat(spec.path("paths").fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "/api/hotels",
            "/api/hotels/{hotelId}/occupancy",
            "/api/hotels/analytics",
            "/api/rooms",
            "/api/rooms/recommend",
            "/api/rooms/{id}/confirm-availability",
            "/api/rooms/{id}/release");
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
    assertThat(spec.at("/components/securitySchemes/internalKey/name").asText())
        .isEqualTo("X-Internal-Key");
    assertThat(spec.at("/paths/~1api~1rooms~1{id}~1release/post/security/0").has("internalKey"))
        .isTrue();
    assertThat(
            spec.at("/paths/~1api~1rooms/post/requestBody/content/application~1json/schema/$ref")
                .asText())
        .isEqualTo("#/components/schemas/CreateRoomRequest");
  }
}
