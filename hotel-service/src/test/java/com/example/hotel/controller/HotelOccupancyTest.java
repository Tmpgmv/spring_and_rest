package com.example.hotel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.entity.Hotel;
import com.example.hotel.entity.Room;
import com.example.hotel.entity.RoomSlot;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Import(HotelOccupancyTest.FixedTime.class)
@Transactional
class HotelOccupancyTest {
  private static final LocalDate TODAY = LocalDate.of(2030, 6, 1);
  @Autowired MockMvc mvc;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired RoomSlotRepository slots;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired Clock clock;

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  String secret;

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  String bearer(String role) throws Exception {
    Instant now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("booking-service")
                .subject("occupancy-test")
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    jwt.sign(new MACSigner(Base64.getDecoder().decode(secret)));
    return "Bearer " + jwt.serialize();
  }

  Hotel hotel() {
    return hotels.saveAndFlush(new Hotel("Occupancy Hotel", "Occupancy address"));
  }

  void hold(Room room, int startOffset, int endOffset) {
    slots.saveAndFlush(
        new RoomSlot(
            room,
            TODAY.plusDays(startOffset),
            TODAY.plusDays(endOffset),
            UUID.randomUUID().toString(),
            clock.instant()));
  }

  /*
   * Исходные данные: На 01.06.2030 созданы три номера выбранного отеля, включая ремонтный, и номер другого
   * отеля; слоты заданы до, после и на текущую дату, счётчики равны 7, 3 и 99; запрос выполняет ADMIN.
   * Ожидаемый результат: Ответ 200 содержит три номера, два занятых, сумму счётчиков 10 и четыре слота;
   * счётчик первого номера остаётся 7, общее число слотов — 6.
   */
  @DisplayName("Статистика отеля учитывает его номера, слоты и счётчики без данных других отелей")
  @Test
  void adminSeesPerRoomStatisticsAndHotelTotalsWithoutOtherHotelsData() throws Exception {
    Hotel hotel = hotel();
    Room first = rooms.saveAndFlush(new Room(hotel, "101"));
    Room second = rooms.saveAndFlush(new Room(hotel, "102"));
    Room maintenance = new Room(hotel, "103");
    maintenance.setAvailable(false);
    rooms.saveAndFlush(maintenance);
    Room other = rooms.saveAndFlush(new Room(hotel(), "999"));
    hold(first, 0, 2);
    hold(first, 5, 7);
    hold(second, -3, 0);
    hold(maintenance, -1, 1);
    hold(other, 0, 1);
    jdbc.update("update rooms set times_booked = 7 where id = ?", first.getId());
    jdbc.update("update rooms set times_booked = 3 where id = ?", second.getId());
    jdbc.update("update rooms set times_booked = 99 where id = ?", other.getId());
    entityManager.clear();

    mvc.perform(
            get("/api/hotels/{hotelId}/occupancy", hotel.getId())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.hotelId").value(hotel.getId()))
        .andExpect(jsonPath("$.asOfDate").value("2030-06-01"))
        .andExpect(jsonPath("$.rooms.length()").value(3))
        .andExpect(jsonPath("$.rooms[0].roomId").value(first.getId()))
        .andExpect(jsonPath("$.rooms[0].number").value("101"))
        .andExpect(jsonPath("$.rooms[0].available").value(true))
        .andExpect(jsonPath("$.rooms[0].timesBooked").value(7))
        .andExpect(jsonPath("$.rooms[0].heldSlots").value(2))
        .andExpect(jsonPath("$.rooms[0].occupied").value(true))
        .andExpect(jsonPath("$.rooms[1].roomId").value(second.getId()))
        .andExpect(jsonPath("$.rooms[1].timesBooked").value(3))
        .andExpect(jsonPath("$.rooms[1].heldSlots").value(1))
        .andExpect(jsonPath("$.rooms[1].occupied").value(false))
        .andExpect(jsonPath("$.rooms[2].roomId").value(maintenance.getId()))
        .andExpect(jsonPath("$.rooms[2].available").value(false))
        .andExpect(jsonPath("$.rooms[2].timesBooked").value(0))
        .andExpect(jsonPath("$.rooms[2].heldSlots").value(1))
        .andExpect(jsonPath("$.rooms[2].occupied").value(true))
        .andExpect(jsonPath("$.aggregate.totalRooms").value(3))
        .andExpect(jsonPath("$.aggregate.occupiedRooms").value(2))
        .andExpect(jsonPath("$.aggregate.timesBooked").value(10))
        .andExpect(jsonPath("$.aggregate.heldSlots").value(4));
    assertThat(
            jdbc.queryForObject(
                "select times_booked from rooms where id = ?", Long.class, first.getId()))
        .isEqualTo(7L);
    assertThat(slots.count()).isEqualTo(6);
  }

  /*
   * Исходные данные: Демонстрационные данные Central Hotel и фиксированная дата 01.06.2030; запрос
   * статистики с токеном ADMIN.
   * Ожидаемый результат: Ответ 200 содержит два номера, первый занят с одним слотом, второй свободен;
   * итоги: два номера, один занятый, одно бронирование и один слот.
   */
  @DisplayName("Демонстрационный отель отражает подтверждённое бронирование в статистике")
  @Test
  void seededHotelReflectsConfirmedBooking() throws Exception {
    Hotel hotel =
        hotels.findAll().stream()
            .filter(h -> h.getName().equals("Central Hotel"))
            .findFirst()
            .orElseThrow();
    mvc.perform(
            get("/api/hotels/{id}/occupancy", hotel.getId())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms.length()").value(2))
        .andExpect(jsonPath("$.rooms[0].heldSlots").value(1))
        .andExpect(jsonPath("$.rooms[0].occupied").value(true))
        .andExpect(jsonPath("$.rooms[1].heldSlots").value(0))
        .andExpect(jsonPath("$.rooms[1].occupied").value(false))
        .andExpect(jsonPath("$.aggregate.totalRooms").value(2))
        .andExpect(jsonPath("$.aggregate.occupiedRooms").value(1))
        .andExpect(jsonPath("$.aggregate.timesBooked").value(1))
        .andExpect(jsonPath("$.aggregate.heldSlots").value(1));
  }

  /*
   * Исходные данные: Создан отель без номеров; его статистику запрашивает ADMIN.
   * Ожидаемый результат: Ответ 200 содержит пустой список номеров и нули во всех итоговых счётчиках.
   */
  @DisplayName("Отель без номеров возвращает пустую статистику и нулевые итоги")
  @Test
  void hotelWithoutRoomsReturnsEmptyStatisticsAndZeroTotals() throws Exception {
    mvc.perform(
            get("/api/hotels/{id}/occupancy", hotel().getId())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms").isEmpty())
        .andExpect(jsonPath("$.aggregate.totalRooms").value(0))
        .andExpect(jsonPath("$.aggregate.occupiedRooms").value(0))
        .andExpect(jsonPath("$.aggregate.timesBooked").value(0))
        .andExpect(jsonPath("$.aggregate.heldSlots").value(0));
  }

  /*
   * Исходные данные: Параметры start и end задают смещения границ единственного слота относительно
   * 01.06.2030; occupied задаёт ожидаемую занятость для каждого набора CsvSource; запрос выполняет ADMIN.
   * Ожидаемый результат: Ответ 200 содержит occupied из параметра, соответствующее число занятых номеров 0
   * или 1 и один слот независимо от его дат.
   */
  @DisplayName("Текущая занятость учитывает исключённую дату выезда")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"-1,0,false", "0,1,true", "-1,1,true", "1,2,false"})
  void currentOccupancyUsesHalfOpenDatesWhileHeldCountIncludesAllDates(
      int start, int end, boolean occupied) throws Exception {
    Hotel hotel = hotel();
    hold(rooms.saveAndFlush(new Room(hotel, "101")), start, end);
    mvc.perform(
            get("/api/hotels/{id}/occupancy", hotel.getId())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].occupied").value(occupied))
        .andExpect(jsonPath("$.rooms[0].heldSlots").value(1))
        .andExpect(jsonPath("$.aggregate.occupiedRooms").value(occupied ? 1 : 0))
        .andExpect(jsonPath("$.aggregate.heldSlots").value(1));
  }

  /*
   * Исходные данные: ADMIN запрашивает статистику отеля с идентификатором Long.MAX_VALUE и заголовком
   * X-Trace-Id=occupancy-missing.
   * Ожидаемый результат: Ответ 404 содержит ErrorDTO с сообщением Hotel not found, исходным путём и traceId
   * occupancy-missing.
   */
  @DisplayName("Статистика неизвестного отеля возвращает ошибку 404 с идентификатором трассировки")
  @Test
  void unknownHotelReturns404ErrorDto() throws Exception {
    mvc.perform(
            get("/api/hotels/{id}/occupancy", Long.MAX_VALUE)
                .header("Authorization", bearer("ADMIN"))
                .header("X-Trace-Id", "occupancy-missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Hotel not found"))
        .andExpect(jsonPath("$.path").value("/api/hotels/" + Long.MAX_VALUE + "/occupancy"))
        .andExpect(jsonPath("$.traceId").value("occupancy-missing"));
  }

  /*
   * Исходные данные: Запросы статистики существующего и неизвестного отелей выполняются без токена и с
   * токеном USER; во втором случае traceId равен occupancy-denied.
   * Ожидаемый результат: Для обоих отелей анонимный запрос возвращает 401, запрос USER — 403 с traceId
   * occupancy-denied и соответствующим описанием ошибки.
   */
  @DisplayName("Статистика закрыта для анонимного клиента и роли USER при любом отеле")
  @Test
  void anonymousAndUserCallersCannotReadOccupancyEvenForUnknownHotels() throws Exception {
    for (Long hotelId : new Long[] {hotels.findAll().get(0).getId(), Long.MAX_VALUE}) {
      mvc.perform(get("/api/hotels/{id}/occupancy", hotelId))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.error").value("Unauthorized"));
      mvc.perform(
              get("/api/hotels/{id}/occupancy", hotelId)
                  .header("Authorization", bearer("USER"))
                  .header("X-Trace-Id", "occupancy-denied"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.status").value(403))
          .andExpect(jsonPath("$.error").value("Forbidden"))
          .andExpect(jsonPath("$.traceId").value("occupancy-denied"));
    }
  }
}
