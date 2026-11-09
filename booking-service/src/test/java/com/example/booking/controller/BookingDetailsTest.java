package com.example.booking.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.booking.entity.Booking;
import com.example.booking.entity.BookingStatus;
import com.example.booking.entity.User;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.UserRepository;
import com.example.booking.service.JwtTokenService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"eureka.client.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class BookingDetailsTest {
  @Autowired MockMvc mvc;
  @Autowired BookingRepository bookings;
  @Autowired UserRepository users;
  @Autowired JwtTokenService tokens;
  @Autowired EntityManager entityManager;

  private Long bookingId;
  private String authorization;

  @BeforeEach
  void setUp() {
    User owner = users.save(new User("details-owner", "unused-hash", UserRole.USER));
    users.save(new User("details-other", "unused-hash", UserRole.USER));
    bookingId =
        bookings
            .saveAndFlush(
                new Booking(
                    owner,
                    42L,
                    LocalDate.of(2030, 6, 10),
                    LocalDate.of(2030, 6, 15),
                    Instant.parse("2030-06-01T12:00:00Z")))
            .getId();
    authorization = "Bearer " + tokens.issueToken(owner.getUsername(), UserRole.USER);
    entityManager.clear();
  }

  /*
   * Исходные данные: Фикстура создаёт бронирование номера 42 на 10–15 июня 2030 года; параметр bookingStatus
   * перебирает все статусы и задаёт состояние перед запросом владельца.
   * Ожидаемый результат: Для каждого статуса ответ 200 содержит ровно шесть полей с исходным идентификатором,
   * номером, датами, временем создания и переданным статусом.
   */
  @ParameterizedTest(name = "[{index}] {arguments}")
  @DisplayName("Владелец получает детали бронирования в любом статусе")
  @EnumSource(BookingStatus.class)
  void ownerGetsBookingDetailsInEveryStatus(BookingStatus bookingStatus) throws Exception {
    if (bookingStatus != BookingStatus.PENDING) bookings.completePending(bookingId, bookingStatus);
    entityManager.clear();
    mvc.perform(get("/booking/{id}", bookingId).header("Authorization", authorization))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$", aMapWithSize(6)))
        .andExpect(jsonPath("$.id").value(bookingId))
        .andExpect(jsonPath("$.roomId").value(42))
        .andExpect(jsonPath("$.startDate").value("2030-06-10"))
        .andExpect(jsonPath("$.endDate").value("2030-06-15"))
        .andExpect(jsonPath("$.status").value(bookingStatus.name()))
        .andExpect(jsonPath("$.createdAt").value("2030-06-01T12:00:00Z"));
  }

  /*
   * Исходные данные: Пользователь details-other запрашивает бронирование details-owner и Long.MAX_VALUE с
   * traceId=details-trace и подставленным username владельца.
   * Ожидаемый результат: Оба запроса дают одинаковый контракт ErrorDTO из шести полей со статусом 404,
   * сообщением Booking not found, соответствующим путём и исходным traceId.
   */
  @Test
  @DisplayName("Чужое и отсутствующее бронирования скрываются одинаковым ответом")
  void foreignAndMissingBookingsReturnSameErrorContract() throws Exception {
    String otherAuthorization = "Bearer " + tokens.issueToken("details-other", UserRole.USER);
    for (Long id : new Long[] {bookingId, Long.MAX_VALUE}) {
      mvc.perform(
              get("/booking/{id}", id)
                  .header("Authorization", otherAuthorization)
                  .header("X-Trace-Id", "details-trace")
                  .param("username", "details-owner"))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$", aMapWithSize(6)))
          .andExpect(jsonPath("$.timestamp").isString())
          .andExpect(jsonPath("$.status").value(404))
          .andExpect(jsonPath("$.error").value("Not Found"))
          .andExpect(jsonPath("$.message").value("Booking not found"))
          .andExpect(jsonPath("$.path").value("/booking/" + id))
          .andExpect(jsonPath("$.traceId").value("details-trace"));
    }
  }

  /*
   * Исходные данные: Запросы деталей бронирования выполняются без токена, с Bearer invalid и с действительным
   * токеном роли ADMIN.
   * Ожидаемый результат: Первые два запроса возвращают 401, запрос ADMIN — 403.
   */
  @Test
  @DisplayName("Доступ к деталей бронирования требует аутентификации и роли USER")
  void requiresAuthenticationAndUserRole() throws Exception {
    mvc.perform(get("/booking/{id}", bookingId)).andExpect(status().isUnauthorized());
    mvc.perform(get("/booking/{id}", bookingId).header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/booking/{id}", bookingId)
                .header("Authorization", "Bearer " + tokens.issueToken("admin", UserRole.ADMIN)))
        .andExpect(status().isForbidden());
  }
}
