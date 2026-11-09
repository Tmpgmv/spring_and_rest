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
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "spring.jpa.properties.hibernate.generate_statistics=true"
    })
@AutoConfigureMockMvc
@Import(HotelAnalyticsTest.FixedTime.class)
@Transactional
class HotelAnalyticsTest {
  private static final LocalDate START = LocalDate.of(2000, 6, 2);
  private static final LocalDate END = LocalDate.of(2000, 6, 5);
  @Autowired MockMvc mvc;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired RoomSlotRepository slots;
  @Autowired EntityManager entityManager;
  @Autowired EntityManagerFactory entityManagerFactory;

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

  @BeforeEach
  void clearSeedData() {
    slots.deleteAllInBatch();
    rooms.deleteAllInBatch();
    hotels.deleteAllInBatch();
    entityManager.clear();
  }

  String bearer(String role) throws Exception {
    Instant now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("booking-service")
                .subject("analytics-test")
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    jwt.sign(new MACSigner(Base64.getDecoder().decode(secret)));
    return "Bearer " + jwt.serialize();
  }

  Hotel hotel(String name) {
    return hotels.saveAndFlush(new Hotel(name, "Analytics address"));
  }

  Room room(Hotel hotel, String number) {
    return rooms.saveAndFlush(new Room(hotel, number));
  }

  void hold(Room room, LocalDate start, LocalDate end) {
    slots.saveAndFlush(
        new RoomSlot(
            room, start, end, UUID.randomUUID().toString(), Instant.parse("1999-01-01T00:00:00Z")));
  }

  MockHttpServletRequestBuilder request() {
    return get("/api/hotels/analytics")
        .param("startDate", START.toString())
        .param("endDate", END.toString());
  }

  ResultActions analytics(Long hotelId) throws Exception {
    var request = request().header("Authorization", bearer("ADMIN"));
    if (hotelId != null) request.param("hotelId", hotelId.toString());
    return mvc.perform(request);
  }

  /*
   * Исходные данные: После очистки данных создан номер 101 со слотом 03–05.06.2000; ADMIN запрашивает
   * период 02–05.06.2000.
   * Ожидаемый результат: Ответ 200 содержит три ночи ёмкости, две занятые ночи и 66,7 процента для номера и
   * итога, а также даты и реквизиты отеля и номера.
   */
  @DisplayName("Исторический слот учитывает две ночи из трёх и округляет загрузку до 66,7 процента")
  @Test
  void fullyInsideHistoricalSlotCountsNightsAndRoundsHalfUp() throws Exception {
    Hotel hotel = hotel("Historical hotel");
    Room room = room(hotel, "101");
    hold(room, START.plusDays(1), END);

    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startDate").value(START.toString()))
        .andExpect(jsonPath("$.endDate").value(END.toString()))
        .andExpect(jsonPath("$.nights").value(3))
        .andExpect(jsonPath("$.total.availableRoomNights").value(3))
        .andExpect(jsonPath("$.total.bookedRoomNights").value(2))
        .andExpect(jsonPath("$.total.percent").value(66.7))
        .andExpect(jsonPath("$.hotels[0].name").value("Historical hotel"))
        .andExpect(jsonPath("$.hotels[0].rooms[0].roomId").value(room.getId()))
        .andExpect(jsonPath("$.hotels[0].rooms[0].number").value("101"))
        .andExpect(jsonPath("$.hotels[0].rooms[0].bookedRoomNights").value(2))
        .andExpect(jsonPath("$.hotels[0].rooms[0].percent").value(66.7));
  }

  /*
   * Исходные данные: Создан номер со слотом 01–04.06.2000; ADMIN запрашивает период 02–05.06.2000.
   * Ожидаемый результат: Ответ 200 учитывает две занятые ночи внутри периода.
   */
  @DisplayName("Занятые ночи обрезаются по началу периода отчёта")
  @Test
  void startOverlapClipsToQueryStart() throws Exception {
    Hotel hotel = hotel("Start overlap");
    hold(room(hotel, "101"), START.minusDays(1), END.minusDays(1));
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.bookedRoomNights").value(2));
  }

  /*
   * Исходные данные: Создан номер со слотом 03–07.06.2000; ADMIN запрашивает период 02–05.06.2000.
   * Ожидаемый результат: Ответ 200 учитывает две занятые ночи внутри периода.
   */
  @DisplayName("Занятые ночи обрезаются по концу периода отчёта")
  @Test
  void endOverlapClipsToQueryEnd() throws Exception {
    Hotel hotel = hotel("End overlap");
    hold(room(hotel, "101"), START.plusDays(1), END.plusDays(2));
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.bookedRoomNights").value(2));
  }

  /*
   * Исходные данные: Параметры startOffset и endOffset задают границы слота в днях относительно 02.06.2000;
   * все пары CsvSource лежат вне периода 02–05.06.2000 или примыкают к нему; запрос выполняет ADMIN.
   * Ожидаемый результат: Ответ 200 содержит ноль занятых ночей для номера и итога, итоговая загрузка равна
   * нулю.
   */
  @DisplayName("Смежные и внешние слоты не добавляют занятых ночей")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"-4,-2", "-2,0", "3,5", "5,7"})
  void outsideAndAdjacentSlotsContributeZeroNights(int startOffset, int endOffset)
      throws Exception {
    Hotel hotel = hotel("Outside");
    hold(room(hotel, "101"), START.plusDays(startOffset), START.plusDays(endOffset));
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.bookedRoomNights").value(0))
        .andExpect(jsonPath("$.total.percent").value(0.0))
        .andExpect(jsonPath("$.hotels[0].rooms[0].bookedRoomNights").value(0));
  }

  /*
   * Исходные данные: Созданы свободный и ремонтный номера; ремонтный занят на весь период 02–05.06.2000;
   * запрос выполняет ADMIN.
   * Ожидаемый результат: Ответ 200 содержит два номера, ёмкость три ночи и три занятые ночи, итог 100
   * процентов; у свободного номера нулевая загрузка, у ремонтного процент отсутствует.
   */
  @DisplayName("Ремонтный номер добавляет занятые ночи без увеличения ёмкости")
  @Test
  void unavailableRoomContributesBookingsButNoCapacityOrRoomPercent() throws Exception {
    Hotel hotel = hotel("Maintenance");
    room(hotel, "101");
    Room maintenance = room(hotel, "102");
    maintenance.setAvailable(false);
    hold(maintenance, START, END);

    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.availableRoomNights").value(3))
        .andExpect(jsonPath("$.total.bookedRoomNights").value(3))
        .andExpect(jsonPath("$.total.percent").value(100.0))
        .andExpect(jsonPath("$.hotels[0].rooms.length()").value(2))
        .andExpect(jsonPath("$.hotels[0].rooms[0].bookedRoomNights").value(0))
        .andExpect(jsonPath("$.hotels[0].rooms[0].percent").value(0.0))
        .andExpect(jsonPath("$.hotels[0].rooms[1].bookedRoomNights").value(3))
        .andExpect(jsonPath("$.hotels[0].rooms[1].percent").doesNotExist());
  }

  /*
   * Исходные данные: Создан отель с одним номером без слотов; ADMIN запрашивает период 02–05.06.2000.
   * Ожидаемый результат: Ответ 200 содержит номер, ноль занятых ночей и нулевые проценты номера, отеля и
   * итога.
   */
  @DisplayName("Номер без слотов включается в отчёт с нулевой загрузкой")
  @Test
  void rangeWithoutSlotsIncludesZeroBookingRoomsAndZeroPercentages() throws Exception {
    Hotel hotel = hotel("Empty period");
    room(hotel, "101");
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.bookedRoomNights").value(0))
        .andExpect(jsonPath("$.total.percent").value(0.0))
        .andExpect(jsonPath("$.hotels[0].percent").value(0.0))
        .andExpect(jsonPath("$.hotels[0].rooms.length()").value(1))
        .andExpect(jsonPath("$.hotels[0].rooms[0].bookedRoomNights").value(0))
        .andExpect(jsonPath("$.hotels[0].rooms[0].percent").value(0.0));
  }

  /*
   * Исходные данные: У выбранного отеля забронирована одна ночь, у другого — три; ADMIN запрашивает период
   * 02–05.06.2000 с hotelId выбранного отеля.
   * Ожидаемый результат: Ответ 200 содержит только выбранный отель, три ночи ёмкости и одну занятую ночь;
   * все итоговые показатели совпадают с показателями отеля.
   */
  @DisplayName("Фильтр отеля исключает чужие бронирования и согласует итоги")
  @Test
  void existingHotelFilterExcludesOtherHotelsAndMatchesTotal() throws Exception {
    Hotel selected = hotel("Selected");
    hold(room(selected, "101"), START, START.plusDays(1));
    hold(room(hotel("Other"), "999"), START, END);

    var result =
        analytics(selected.getId())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hotels.length()").value(1))
            .andExpect(jsonPath("$.hotels[0].hotelId").value(selected.getId()))
            .andExpect(jsonPath("$.total.availableRoomNights").value(3))
            .andExpect(jsonPath("$.total.bookedRoomNights").value(1))
            .andReturn();
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString());
    for (String field : new String[] {"availableRoomNights", "bookedRoomNights", "percent"}) {
      assertThat(json.path("total").path(field)).isEqualTo(json.path("hotels").get(0).path(field));
    }
  }

  /*
   * Исходные данные: На очищенных данных ADMIN запрашивает период 02–05.06.2000 с hotelId=Long.MAX_VALUE и
   * traceId analytics-missing.
   * Ожидаемый результат: Ответ 404 содержит Hotel not found, путь /api/hotels/analytics и traceId
   * analytics-missing.
   */
  @DisplayName("Аналитика неизвестного отеля возвращает 404 с исходной трассировкой")
  @Test
  void unknownHotelReturns404ErrorDto() throws Exception {
    mvc.perform(
            request()
                .param("hotelId", Long.toString(Long.MAX_VALUE))
                .header("Authorization", bearer("ADMIN"))
                .header("X-Trace-Id", "analytics-missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").value("Hotel not found"))
        .andExpect(jsonPath("$.path").value("/api/hotels/analytics"))
        .andExpect(jsonPath("$.traceId").value("analytics-missing"));
  }

  /*
   * Исходные данные: Период 02–05.06.2000 запрашивается с токеном USER.
   * Ожидаемый результат: Ответ 403 содержит статус 403 в ErrorDTO.
   */
  @DisplayName("Роль USER не получает доступ к аналитике")
  @Test
  void userCannotReadAnalytics() throws Exception {
    mvc.perform(request().header("Authorization", bearer("USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  /*
   * Исходные данные: Период 02–05.06.2000 запрашивается без токена.
   * Ожидаемый результат: Ответ 401 содержит статус 401 в ErrorDTO.
   */
  @DisplayName("Аналитика без токена возвращает 401")
  @Test
  void missingTokenReturns401ErrorDto() throws Exception {
    mvc.perform(request())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  /*
   * Исходные данные: Параметры start и end из CsvSource задают равные даты либо выезд раньше заезда; запрос
   * выполняет ADMIN с traceId analytics-invalid.
   * Ожидаемый результат: Ответ 400 содержит Validation failed, исходный путь и traceId, одну ошибку поля
   * endDate с сообщением must be after startDate.
   */
  @DisplayName("Равные и обратные даты аналитики возвращают ошибку endDate")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"2000-06-02,2000-06-02", "2000-06-05,2000-06-02"})
  void equalOrReversedDatesReturn400WithFieldErrors(String start, String end) throws Exception {
    mvc.perform(
            get("/api/hotels/analytics")
                .param("startDate", start)
                .param("endDate", end)
                .header("Authorization", bearer("ADMIN"))
                .header("X-Trace-Id", "analytics-invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.path").value("/api/hotels/analytics"))
        .andExpect(jsonPath("$.traceId").value("analytics-invalid"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(1))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("endDate"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("must be after startDate"));
  }

  /*
   * Исходные данные: Созданы отели с тремя и одной занятыми ночами и отель без номеров; контекст и
   * статистика Hibernate очищены; ADMIN запрашивает период 02–05.06.2000 без фильтра.
   * Ожидаемый результат: Ответ 200 содержит три отеля по идентификатору, пустые номера третьего отеля,
   * ёмкость шесть ночей, четыре занятые ночи и 66,7 процента; выполнены три SQL-запроса.
   */
  @DisplayName("Общий отчёт агрегирует три отеля за три SQL-запроса")
  @Test
  void unfilteredReportAggregatesHotelsWithConstantQueryCount() throws Exception {
    Hotel first = hotel("First");
    hold(room(first, "101"), START, END);
    Hotel second = hotel("Second");
    hold(room(second, "201"), START, START.plusDays(1));
    Hotel empty = hotel("No rooms");
    entityManager.flush();
    entityManager.clear();
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    analytics(null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hotels.length()").value(3))
        .andExpect(jsonPath("$.hotels[0].hotelId").value(first.getId()))
        .andExpect(jsonPath("$.hotels[1].hotelId").value(second.getId()))
        .andExpect(jsonPath("$.hotels[2].hotelId").value(empty.getId()))
        .andExpect(jsonPath("$.hotels[2].rooms").isEmpty())
        .andExpect(jsonPath("$.total.availableRoomNights").value(6))
        .andExpect(jsonPath("$.total.bookedRoomNights").value(4))
        .andExpect(jsonPath("$.total.percent").value(66.7));
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
  }

  /*
   * Исходные данные: Слот номера начинается за десять дней до 02.06.2000 и заканчивается через десять дней
   * после 05.06.2000; запрос выполняет ADMIN.
   * Ожидаемый результат: Ответ 200 учитывает три занятые ночи и загрузку 100 процентов.
   */
  @DisplayName("Слот шире периода обрезается по обеим границам отчёта")
  @Test
  void slotCoveringEntireRangeIsClippedAtBothEnds() throws Exception {
    Hotel hotel = hotel("Both ends");
    hold(room(hotel, "101"), START.minusDays(10), END.plusDays(10));
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.bookedRoomNights").value(3))
        .andExpect(jsonPath("$.total.percent").value(100.0));
  }

  /*
   * Исходные данные: При фиксированной дате 01.06.2030 параметры start и end из CsvSource задают прошлый,
   * пересекающий настоящее, текущий или будущий период; запрос выполняет ADMIN.
   * Ожидаемый результат: Каждый набор возвращает 200 и переданные startDate и endDate без изменений.
   */
  @DisplayName("Аналитика принимает прошлые, текущие и будущие периоды")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({
    "2000-01-01,2001-01-01",
    "2029-12-01,2030-07-01",
    "2030-06-01,2030-06-02",
    "2040-01-01,2041-01-01"
  })
  void pastSpanningPresentAndFuturePeriodsAreAccepted(String start, String end) throws Exception {
    mvc.perform(
            get("/api/hotels/analytics")
                .param("startDate", start)
                .param("endDate", end)
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.startDate").value(start))
        .andExpect(jsonPath("$.endDate").value(end));
  }

  /*
   * Исходные данные: Один номер занят одну ночь с 02.06.2000; ADMIN запрашивает период из шестнадцати ночей
   * с фильтром отеля.
   * Ожидаемый результат: Ответ 200 содержит ёмкость 16 ночей, одну занятую ночь и процент 6,3.
   */
  @DisplayName("Загрузка 6,25 процента округляется до 6,3 процента")
  @Test
  void exactRoundingTieUsesHalfUp() throws Exception {
    Hotel hotel = hotel("Rounding");
    hold(room(hotel, "101"), START, START.plusDays(1));
    mvc.perform(
            get("/api/hotels/analytics")
                .param("startDate", START.toString())
                .param("endDate", START.plusDays(16).toString())
                .param("hotelId", hotel.getId().toString())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.availableRoomNights").value(16))
        .andExpect(jsonPath("$.total.bookedRoomNights").value(1))
        .andExpect(jsonPath("$.total.percent").value(6.3));
  }

  /*
   * Исходные данные: Единственный номер недоступен и занят на весь период 02–05.06.2000; запрос выполняет
   * ADMIN.
   * Ожидаемый результат: Ответ 200 содержит нулевую ёмкость и три занятые ночи; проценты итога, отеля и
   * номера отсутствуют.
   */
  @DisplayName("Нулевая ёмкость исключает неопределённые проценты загрузки")
  @Test
  void zeroCapacityOmitsUndefinedPercentages() throws Exception {
    Hotel hotel = hotel("Unavailable");
    Room room = room(hotel, "101");
    room.setAvailable(false);
    hold(room, START, END);
    analytics(hotel.getId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total.availableRoomNights").value(0))
        .andExpect(jsonPath("$.total.bookedRoomNights").value(3))
        .andExpect(jsonPath("$.total.percent").doesNotExist())
        .andExpect(jsonPath("$.hotels[0].percent").doesNotExist())
        .andExpect(jsonPath("$.hotels[0].rooms[0].percent").doesNotExist());
  }
}
