package com.example.hotel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.entity.Hotel;
import com.example.hotel.entity.Room;
import com.example.hotel.entity.RoomSlot;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Import(RoomAvailabilityTest.FixedTime.class)
@Transactional
class RoomAvailabilityTest {
  private static final LocalDate TODAY = LocalDate.of(2030, 6, 1);
  private static final LocalDate START = LocalDate.of(2030, 6, 10);
  private static final LocalDate END = LocalDate.of(2030, 6, 15);
  @Autowired MockMvc mvc;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired RoomSlotRepository slots;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  String secret;

  Hotel firstHotel;
  Hotel secondHotel;
  Room first;
  Room second;
  Room otherHotel;
  Room maintenance;

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @BeforeEach
  void fixtures() {
    slots.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    hotels.flush();
    firstHotel = hotels.saveAndFlush(new Hotel("First Hotel", "First address"));
    secondHotel = hotels.saveAndFlush(new Hotel("Second Hotel", "Second address"));
    first = rooms.saveAndFlush(new Room(firstHotel, "101"));
    second = rooms.saveAndFlush(new Room(firstHotel, "102"));
    otherHotel = rooms.saveAndFlush(new Room(secondHotel, "201"));
    maintenance = new Room(firstHotel, "103");
    maintenance.setAvailable(false);
    rooms.saveAndFlush(maintenance);
  }

  String bearer(String role) throws Exception {
    Instant now = Instant.now();
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("booking-service")
                .subject("availability-test")
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    jwt.sign(new MACSigner(Base64.getDecoder().decode(secret)));
    return "Bearer " + jwt.serialize();
  }

  MockHttpServletRequestBuilder query() throws Exception {
    return get("/api/rooms")
        .param("startDate", START.toString())
        .param("endDate", END.toString())
        .header("Authorization", bearer("USER"));
  }

  void hold(Room room, LocalDate start, LocalDate end) {
    slots.saveAndFlush(
        new RoomSlot(room, start, end, UUID.randomUUID().toString(), clock.instant()));
  }

  void assertRooms(MockHttpServletRequestBuilder query, Room... expected) throws Exception {
    var response =
        mvc.perform(query)
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andReturn();
    var result = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(result.isArray()).isTrue();
    List<Long> ids = new ArrayList<>();
    result.forEach(room -> ids.add(room.get("id").asLong()));
    assertThat(ids)
        .containsExactlyInAnyOrderElementsOf(
            java.util.Arrays.stream(expected).map(Room::getId).toList());
  }

  /*
   * Исходные данные: Созданы номера 101 и 102 первого отеля, 201 второго и ремонтный 103; слотов нет; USER
   * запрашивает 10–15.06.2030 без фильтра и с фильтром второго отеля.
   * Ожидаемый результат: Первый ответ 200 содержит три доступных номера; второй — только 201 с правильными
   * id, hotelId, available=true и timesBooked=0.
   */
  @DisplayName("Поиск свободных номеров исключает ремонт и возвращает публичные поля")
  @Test
  void freeRoomsExcludeOperationallyUnavailableRoomsAndExposeRoomDto() throws Exception {
    assertRooms(query(), first, second, otherHotel);
    mvc.perform(query().param("hotelId", secondHotel.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(otherHotel.getId()))
        .andExpect(jsonPath("$[0].hotelId").value(secondHotel.getId()))
        .andExpect(jsonPath("$[0].number").value("201"))
        .andExpect(jsonPath("$[0].available").value(true))
        .andExpect(jsonPath("$[0].timesBooked").value(0));
  }

  /*
   * Исходные данные: Параметры startDay и endDay задают дни июня 2030 для слота номера 101; все пары
   * CsvSource пересекают 10–15 июня; USER запрашивает этот период.
   * Ожидаемый результат: Ответ 200 содержит только номера 102 и 201, исключая занятый 101 и ремонтный 103.
   */
  @DisplayName("Любое пересечение слота исключает только занятый номер")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"9,11", "14,16", "11,14", "9,16", "10,15", "10,12", "12,15"})
  void everyKindOfOverlappingHeldSlotExcludesOnlyItsRoom(int startDay, int endDay)
      throws Exception {
    hold(first, LocalDate.of(2030, 6, startDay), LocalDate.of(2030, 6, endDay));
    assertRooms(query(), second, otherHotel);
  }

  /*
   * Исходные данные: Параметры startDay и endDay задают дни июня 2030 для слота номера 101; пары CsvSource
   * не пересекают 10–15 июня; запрос выполняет USER.
   * Ожидаемый результат: Ответ 200 содержит все три доступных номера: 101, 102 и 201.
   */
  @DisplayName("Смежные и удалённые слоты сохраняют номер свободным")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"5,10", "15,20", "1,5", "20,25"})
  void adjacentAndSeparatedSlotsLeaveRoomFree(int startDay, int endDay) throws Exception {
    hold(first, LocalDate.of(2030, 6, startDay), LocalDate.of(2030, 6, endDay));
    assertRooms(query(), first, second, otherHotel);
  }

  /*
   * Исходные данные: Номера 101 и 201 заняты 10–15.06.2030; USER запрашивает этот период с фильтром
   * первого, второго и неизвестного отеля.
   * Ожидаемый результат: Ответы 200 содержат соответственно только номер 102, пустой список и пустой
   * список.
   */
  @DisplayName("Фильтр отеля применяется вместе с исключением занятых номеров")
  @Test
  void hotelFilterCombinesWithOverlapExclusion() throws Exception {
    hold(first, START, END);
    hold(otherHotel, START, END);
    assertRooms(query().param("hotelId", firstHotel.getId().toString()), second);
    assertRooms(query().param("hotelId", secondHotel.getId().toString()));
    assertRooms(query().param("hotelId", Long.toString(Long.MAX_VALUE)));
  }

  /*
   * Исходные данные: У номера 101 два слота, примыкающих к периоду 10–15.06.2030 с разных сторон, и
   * timesBooked=99; запрос выполняет USER.
   * Ожидаемый результат: Ответ 200 содержит ровно три доступных номера без дублей; счётчик номера 101
   * остаётся 99.
   */
  @DisplayName("Несколько смежных слотов и счётчик не влияют на свободный номер")
  @Test
  void multipleSlotsDoNotDuplicateResultsAndBookingCounterDoesNotDecideAvailability()
      throws Exception {
    hold(first, START.minusDays(5), START);
    hold(first, END, END.plusDays(5));
    jdbc.update("update rooms set times_booked = 99 where id = ?", first.getId());
    assertRooms(query(), first, second, otherHotel);
    assertThat(
            jdbc.queryForObject(
                "select times_booked from rooms where id = ?", Long.class, first.getId()))
        .isEqualTo(99L);
  }

  /*
   * Исходные данные: Все три доступных номера заняты на период 10–15.06.2030; четвёртый на ремонте; запрос
   * выполняет USER.
   * Ожидаемый результат: Ответ 200 содержит пустой JSON-массив.
   */
  @DisplayName("Поиск возвращает пустой список при занятости всех доступных номеров")
  @Test
  void allRoomsOccupiedReturnsEmptyArray() throws Exception {
    hold(first, START, END);
    hold(second, START, END);
    hold(otherHotel, START, END);
    assertRooms(query());
  }

  /*
   * Исходные данные: При фиксированной дате 01.06.2030 USER запрашивает период 01–02.06.2030; слотов нет.
   * Ожидаемый результат: Ответ 200 содержит все три доступных номера.
   */
  @DisplayName("Поиск разрешает заезд в текущую дату")
  @Test
  void todayIsAValidStartDate() throws Exception {
    assertRooms(
        get("/api/rooms")
            .param("startDate", TODAY.toString())
            .param("endDate", TODAY.plusDays(1).toString())
            .header("Authorization", bearer("USER")),
        first,
        second,
        otherHotel);
  }

  /*
   * Исходные данные: Параметры start и end задают равные, обратные либо прошлые даты; field указывает
   * ошибочное поле каждого набора CsvSource; USER передаёт traceId invalid-dates.
   * Ожидаемый результат: Ответ 400 содержит Bad Request, путь /api/rooms, исходный traceId и ошибку поля
   * field.
   */
  @DisplayName("Некорректный период поиска возвращает ошибку соответствующей даты")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({
    "2030-06-10,2030-06-10,endDate",
    "2030-06-15,2030-06-10,endDate",
    "2030-05-31,2030-06-10,startDate"
  })
  void invalidDateRangesReturn400WithFieldErrors(String start, String end, String field)
      throws Exception {
    mvc.perform(
            get("/api/rooms")
                .param("startDate", start)
                .param("endDate", end)
                .header("Authorization", bearer("USER"))
                .header("X-Trace-Id", "invalid-dates"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.path").value("/api/rooms"))
        .andExpect(jsonPath("$.traceId").value("invalid-dates"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
  }

  /*
   * Исходные данные: Параметр missing равен startDate или endDate; USER передаёт только противоположный
   * параметр со значением 10.06.2030.
   * Ожидаемый результат: Ответ 400 содержит ошибку отсутствующего поля missing.
   */
  @DisplayName("Поиск требует обе даты периода")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"startDate", "endDate"})
  void missingRequiredDateReturns400WithFieldErrors(String missing) throws Exception {
    var request = get("/api/rooms").header("Authorization", bearer("USER"));
    request.param(missing.equals("startDate") ? "endDate" : "startDate", START.toString());
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(missing));
  }

  /*
   * Исходные данные: Параметры field и value задают неверное значение даты, невозможную дату либо
   * нечисловой hotelId; остальные даты равны 10–15.06.2030; запрос выполняет USER.
   * Ожидаемый результат: Каждый набор возвращает 400 с ошибкой поля field.
   */
  @DisplayName("Неверный формат параметров поиска возвращает ошибку поля")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({
    "startDate,not-a-date",
    "startDate,2030-02-30",
    "endDate,invalid",
    "hotelId,not-a-number"
  })
  void malformedParametersReturn400WithFieldErrors(String field, String value) throws Exception {
    var request =
        get("/api/rooms")
            .param("startDate", field.equals("startDate") ? value : START.toString())
            .param("endDate", field.equals("endDate") ? value : END.toString())
            .header("Authorization", bearer("USER"));
    if (field.equals("hotelId")) request.param("hotelId", value);
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
  }

  /*
   * Исходные данные: Период 10–15.06.2030 запрашивается без токена и с токеном только ADMIN.
   * Ожидаемый результат: Первый запрос возвращает 401 Unauthorized, второй — 403 Forbidden с
   * соответствующими статусами ErrorDTO.
   */
  @DisplayName("Поиск свободных номеров требует роль USER")
  @Test
  void anonymousCallerReturns401AndAdminWithoutUserRoleReturns403() throws Exception {
    mvc.perform(
            get("/api/rooms").param("startDate", START.toString()).param("endDate", END.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"));
    mvc.perform(
            get("/api/rooms")
                .param("startDate", START.toString())
                .param("endDate", END.toString())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"));
  }

  MockHttpServletRequestBuilder recommendationQuery() throws Exception {
    return get("/api/rooms/recommend")
        .param("startDate", START.toString())
        .param("endDate", END.toString())
        .header("Authorization", bearer("USER"));
  }

  /*
   * Исходные данные: К трём доступным номерам добавлены свободный 202 со смежным слотом и занятый 104;
   * счётчики 101, 102 и 201 равны 7, 2 и 2; USER запрашивает 10–15.06.2030.
   * Ожидаемый результат: Поиск и рекомендации содержат одинаковые четыре свободных номера; рекомендации
   * упорядочены как 202, 102, 201, 101 со счётчиками 0, 2, 2, 7.
   */
  @DisplayName("Рекомендации сортируют свободные номера по счётчику и идентификатору")
  @Test
  void recommendationsContainExactlyFreeRoomsInCounterThenIdOrder() throws Exception {
    Room leastBooked = rooms.saveAndFlush(new Room(secondHotel, "202"));
    Room occupied = rooms.saveAndFlush(new Room(firstHotel, "104"));
    hold(occupied, START, END);
    hold(leastBooked, END, END.plusDays(2));
    jdbc.update("update rooms set times_booked = 7 where id = ?", first.getId());
    jdbc.update(
        "update rooms set times_booked = 2 where id in (?, ?)", second.getId(), otherHotel.getId());
    entityManager.clear();

    // Обе конечные точки должны возвращать одинаковый набор: смежные слоты допустимы, ремонт и
    // занятость исключаются.
    assertRooms(query(), first, second, otherHotel, leastBooked);
    assertRooms(recommendationQuery(), first, second, otherHotel, leastBooked);
    mvc.perform(recommendationQuery())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value(leastBooked.getId()))
        .andExpect(jsonPath("$[0].timesBooked").value(0))
        .andExpect(jsonPath("$[1].id").value(second.getId()))
        .andExpect(jsonPath("$[1].timesBooked").value(2))
        .andExpect(jsonPath("$[2].id").value(otherHotel.getId()))
        .andExpect(jsonPath("$[2].timesBooked").value(2))
        .andExpect(jsonPath("$[3].id").value(first.getId()))
        .andExpect(jsonPath("$[3].timesBooked").value(7));
  }

  /*
   * Исходные данные: Все три доступных номера заняты 10–15.06.2030; USER запрашивает рекомендации на этот
   * период.
   * Ожидаемый результат: Ответ 200 содержит пустой JSON-массив.
   */
  @DisplayName("Рекомендации пусты при занятости всех доступных номеров")
  @Test
  void recommendationsAreEmptyWhenEveryOperationalRoomIsOccupied() throws Exception {
    hold(first, START, END);
    hold(second, START, END);
    hold(otherHotel, START, END);
    mvc.perform(recommendationQuery()).andExpect(status().isOk()).andExpect(content().json("[]"));
  }

  /*
   * Исходные данные: Параметры start и end из CsvSource задают равные, обратные или прошлые даты; field
   * задаёт ожидаемое ошибочное поле; USER передаёт traceId invalid-recommendation.
   * Ожидаемый результат: Ответ 400 содержит исходный traceId, путь /api/rooms/recommend и ошибку поля
   * field.
   */
  @DisplayName("Рекомендации отклоняют некорректные периоды с ошибкой даты")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({
    "2030-06-10,2030-06-10,endDate",
    "2030-06-15,2030-06-10,endDate",
    "2030-05-31,2030-06-10,startDate"
  })
  void recommendationsRejectInvalidDateRanges(String start, String end, String field)
      throws Exception {
    mvc.perform(
            get("/api/rooms/recommend")
                .param("startDate", start)
                .param("endDate", end)
                .header("Authorization", bearer("USER"))
                .header("X-Trace-Id", "invalid-recommendation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.path").value("/api/rooms/recommend"))
        .andExpect(jsonPath("$.traceId").value("invalid-recommendation"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
  }

  /*
   * Исходные данные: Параметр missing задаёт отсутствующее поле startDate или endDate; USER передаёт только
   * другую дату со значением 10.06.2030.
   * Ожидаемый результат: Ответ 400 содержит ошибку поля missing.
   */
  @DisplayName("Рекомендации требуют обе даты периода")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"startDate", "endDate"})
  void recommendationsRequireBothDates(String missing) throws Exception {
    var request = get("/api/rooms/recommend").header("Authorization", bearer("USER"));
    request.param(missing.equals("startDate") ? "endDate" : "startDate", START.toString());
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(missing));
  }

  /*
   * Исходные данные: USER запрашивает рекомендации с startDate=not-a-date и endDate=15.06.2030.
   * Ожидаемый результат: Ответ 400 содержит ошибку поля startDate.
   */
  @DisplayName("Рекомендации отклоняют дату неверного формата")
  @Test
  void recommendationsRejectMalformedDates() throws Exception {
    mvc.perform(
            get("/api/rooms/recommend")
                .param("startDate", "not-a-date")
                .param("endDate", END.toString())
                .header("Authorization", bearer("USER")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("startDate"));
  }

  /*
   * Исходные данные: Период 10–15.06.2030 запрашивается без токена и с токеном ADMIN.
   * Ожидаемый результат: Ответы содержат соответственно 401 Unauthorized и 403 Forbidden.
   */
  @DisplayName("Рекомендации доступны только роли USER")
  @Test
  void recommendationsRequireUserRole() throws Exception {
    mvc.perform(
            get("/api/rooms/recommend")
                .param("startDate", START.toString())
                .param("endDate", END.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"));
    mvc.perform(
            get("/api/rooms/recommend")
                .param("startDate", START.toString())
                .param("endDate", END.toString())
                .header("Authorization", bearer("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"));
  }

  /*
   * Исходные данные: Сохранён слот номера 101 на 10–15.06.2030 с requestId=same-request; второй слот
   * создаётся для номера 102 с тем же идентификатором.
   * Ожидаемый результат: Сохранение второго слота выбрасывает DataIntegrityViolationException.
   */
  @DisplayName("База запрещает повторный requestId для слотов разных номеров")
  @Test
  void slotRequestIdIsUniqueInDatabase() {
    slots.saveAndFlush(new RoomSlot(first, START, END, "same-request", clock.instant()));
    assertThatThrownBy(
            () ->
                slots.saveAndFlush(
                    new RoomSlot(second, START, END, "same-request", clock.instant())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
