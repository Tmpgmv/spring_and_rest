package com.example.booking.controller;

import static org.hamcrest.Matchers.contains;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"eureka.client.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class BookingHistoryTest {
  @Autowired MockMvc mvc;
  @Autowired BookingRepository bookings;
  @Autowired UserRepository users;
  @Autowired JwtTokenService tokens;
  @Autowired EntityManager entityManager;

  private String authorization;

  @BeforeEach
  void setUp() {
    User owner = users.save(new User("history-owner", "unused-hash", UserRole.USER));
    User other = users.save(new User("history-other", "unused-hash", UserRole.USER));
    // Чередуем владельцев, чтобы фильтрация после разбиения на страницы приводила к ошибке.
    save(owner, 10, BookingStatus.PENDING);
    save(other, 99, BookingStatus.CONFIRMED);
    save(owner, 20, BookingStatus.CONFIRMED);
    save(other, 98, BookingStatus.PENDING);
    save(owner, 30, BookingStatus.CANCELLED);
    authorization = "Bearer " + tokens.issueToken(owner.getUsername(), UserRole.USER);
    entityManager.clear();
  }

  /*
   * Исходные данные: У history-owner есть три бронирования номеров 10, 20 и 30 разных статусов, у
   * history-other — два; запрос владельца подменяет username и userId в параметрах.
   * Ожидаемый результат: Ответ 200 содержит страницу 0 размера 20 с тремя собственными бронированиями,
   * ожидаемыми датами и статусами, без данных пользователя и пароля.
   */
  @Test
  @DisplayName("История по умолчанию содержит только безопасные данные владельца")
  void defaultsReturnOnlyOwnBookingsWithSafeFieldsAndOwnTotals() throws Exception {
    mvc.perform(
            get("/bookings")
                .header("Authorization", authorization)
                .param("username", "history-other")
                .param("userId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].id").isNumber())
        .andExpect(jsonPath("$.content[0].startDate").value("2030-06-10"))
        .andExpect(jsonPath("$.content[0].endDate").value("2030-06-15"))
        .andExpect(jsonPath("$.content[0].createdAt").value("2030-06-01T12:00:00Z"))
        .andExpect(jsonPath("$.content[*].roomId", contains(10, 20, 30)))
        .andExpect(jsonPath("$.content[*].status", contains("PENDING", "CONFIRMED", "CANCELLED")))
        .andExpect(jsonPath("$.content[*].user").isEmpty())
        .andExpect(jsonPath("$.content[*].password").isEmpty());
  }

  /*
   * Исходные данные: У владельца три бронирования номеров 10, 20 и 30; запрашиваются страницы 0 и 1 размера 2
   * с sort=roomId,desc.
   * Ожидаемый результат: Первая страница содержит номера 30 и 20, вторая — 10; всего три записи и две
   * страницы, вторая страница последняя.
   */
  @Test
  @DisplayName("История сортируется до разбиения на страницы")
  void honorsPageSizeAndDescendingSortBeforeReturningSecondPage() throws Exception {
    mvc.perform(
            get("/bookings")
                .header("Authorization", authorization)
                .param("page", "0")
                .param("size", "2")
                .param("sort", "roomId,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].roomId", contains(30, 20)))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2));
    mvc.perform(
            get("/bookings")
                .header("Authorization", authorization)
                .param("page", "1")
                .param("size", "2")
                .param("sort", "roomId,desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value(1))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.content[*].roomId", contains(10)))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.last").value(true));
  }

  /*
   * Исходные данные: Общие фикстуры содержат двух владельцев; history-other запрашивает историю с
   * sort=roomId,asc.
   * Ожидаемый результат: Ответ 200 содержит только номера 98 и 99 в указанном порядке и totalElements=2.
   */
  @Test
  @DisplayName("Другой пользователь получает свои бронирования по возрастанию номера")
  void ascendingSortAndOtherCallerUseTheirOwnBookings() throws Exception {
    mvc.perform(
            get("/bookings")
                .header(
                    "Authorization", "Bearer " + tokens.issueToken("history-other", UserRole.USER))
                .param("sort", "roomId,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].roomId", contains(98, 99)))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  /*
   * Исходные данные: Владелец с тремя бронированиями запрашивает page=10 и size=2.
   * Ожидаемый результат: Ответ 200 содержит пустой content, number=10 и totalElements=3.
   */
  @Test
  @DisplayName("Страница за пределами истории пуста и сохраняет общее число записей")
  void beyondLastPageReturnsEmptyContentAndOwnTotals() throws Exception {
    mvc.perform(
            get("/bookings")
                .header("Authorization", authorization)
                .param("page", "10")
                .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.number").value(10))
        .andExpect(jsonPath("$.totalElements").value(3));
  }

  /*
   * Исходные данные: Создаётся history-empty без бронирований и выполняется запрос истории с его токеном
   * USER.
   * Ожидаемый результат: Ответ 200 содержит пустой content и totalElements=0.
   */
  @Test
  @DisplayName("Пользователь без бронирований получает пустую историю")
  void userWithoutBookingsGetsEmptyPage() throws Exception {
    users.saveAndFlush(new User("history-empty", "unused-hash", UserRole.USER));
    mvc.perform(
            get("/bookings")
                .header(
                    "Authorization", "Bearer " + tokens.issueToken("history-empty", UserRole.USER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  /*
   * Исходные данные: Запросы истории выполняются без токена, с Bearer invalid и с действительным токеном роли
   * ADMIN.
   * Ожидаемый результат: Первые два запроса возвращают 401, запрос ADMIN — 403.
   */
  @Test
  @DisplayName("Доступ к истории требует аутентификации и роли USER")
  void requiresAuthenticationAndUserRole() throws Exception {
    mvc.perform(get("/bookings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/bookings").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/bookings")
                .header("Authorization", "Bearer " + tokens.issueToken("admin", UserRole.ADMIN)))
        .andExpect(status().isForbidden());
  }

  private void save(User owner, long roomId, BookingStatus status) {
    Booking booking =
        bookings.saveAndFlush(
            new Booking(
                owner,
                roomId,
                LocalDate.of(2030, 6, 10),
                LocalDate.of(2030, 6, 15),
                Instant.parse("2030-06-01T12:00:00Z")));
    if (status != BookingStatus.PENDING) bookings.completePending(booking.getId(), status);
  }
}
