package com.example.hotel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.entity.Hotel;
import com.example.hotel.entity.Room;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@Transactional
class HotelRoomManagementTest {
  @Autowired MockMvc mvc;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired EntityManager entityManager;
  @Autowired ObjectMapper mapper;

  @Value("${spring.security.oauth2.resourceserver.jwt.secret}")
  String secret;

  String bearer(String role) throws Exception {
    Instant now = Instant.now();
    var token =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("booking-service")
                .subject(role.toLowerCase())
                .claim("role", role)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .build());
    token.sign(new MACSigner(Base64.getDecoder().decode(secret)));
    return "Bearer " + token.serialize();
  }

  /*
   * Исходные данные: Загружены демонстрационные отели; GET /api/hotels выполняется с токеном USER.
   * Ожидаемый результат: Ответ 200 содержит JSON-массив из двух отелей; у каждого ровно три поля id, name и
   * address, значения совпадают с хранилищем.
   */
  @DisplayName("Каталог возвращает пользователю два демонстрационных отеля с публичными полями")
  @Test
  void userListsAllSeededHotelsWithOnlyPublicHotelFields() throws Exception {
    var response =
        mvc.perform(get("/api/hotels").header("Authorization", bearer("USER")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();
    var actual = mapper.readTree(response.getResponse().getContentAsString());
    assertThat(actual.isArray()).isTrue();
    assertThat(actual.size()).isEqualTo(2);
    var expected =
        hotels.findAll().stream()
            .map(
                hotel ->
                    org.assertj.core.api.Assertions.tuple(
                        hotel.getId(), hotel.getName(), hotel.getAddress()))
            .toList();
    java.util.List<org.assertj.core.groups.Tuple> actualHotels = new java.util.ArrayList<>();
    actual.forEach(
        hotel -> {
          assertThat(hotel.size()).isEqualTo(3);
          actualHotels.add(
              org.assertj.core.api.Assertions.tuple(
                  hotel.get("id").asLong(),
                  hotel.get("name").asText(),
                  hotel.get("address").asText()));
        });
    assertThat(actualHotels).containsExactlyInAnyOrderElementsOf(expected);
  }

  /*
   * Исходные данные: Все слоты, номера и отели удалены; GET /api/hotels выполняется с токеном USER.
   * Ожидаемый результат: Ответ 200 содержит пустой JSON-массив.
   */
  @DisplayName("Пустой каталог возвращает пустой массив")
  @Test
  void userReceivesEmptyArrayWhenCatalogIsEmpty() throws Exception {
    entityManager.createQuery("delete from RoomSlot").executeUpdate();
    rooms.deleteAll();
    hotels.deleteAll();
    entityManager.flush();
    mvc.perform(get("/api/hotels").header("Authorization", bearer("USER")))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  /*
   * Исходные данные: GET /api/hotels выполняется без токена с traceId hotel-list-anonymous.
   * Ожидаемый результат: Ответ 401 содержит Unauthorized, сообщение, время, исходный путь и traceId.
   */
  @DisplayName("Анонимный запрос каталога возвращает 401 с трассировкой")
  @Test
  void anonymousHotelListingReturns401ErrorDto() throws Exception {
    mvc.perform(get("/api/hotels").header("X-Trace-Id", "hotel-list-anonymous"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.path").value("/api/hotels"))
        .andExpect(jsonPath("$.traceId").value("hotel-list-anonymous"));
  }

  /*
   * Исходные данные: GET /api/hotels выполняется с токеном ADMIN без роли USER.
   * Ожидаемый результат: Ответ 403 содержит Forbidden и путь /api/hotels.
   */
  @DisplayName("Токен только с ролью ADMIN не даёт доступ к списку отелей")
  @Test
  void tokenWithoutUserRoleCannotListHotels() throws Exception {
    mvc.perform(get("/api/hotels").header("Authorization", bearer("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.error").value("Forbidden"))
        .andExpect(jsonPath("$.path").value("/api/hotels"));
  }

  /*
   * Исходные данные: Контекст приложения загружен с демонстрационными данными.
   * Ожидаемый результат: Хранилище содержит Central Hotel и Riverside Hotel с заданными адресами и четырьмя
   * доступными номерами 101, 102, 201, 202; их счётчики равны 1, 0, 3, 1, связи с отелями существуют.
   */
  @DisplayName("Начальные данные содержат два отеля и четыре номера с историческими счётчиками")
  @Test
  void startupSeedsLoadBothHotelsAndTheirRoomsWithHistoricalCounters() {
    assertThat(hotels.findAll())
        .extracting(Hotel::getName)
        .containsExactlyInAnyOrder("Central Hotel", "Riverside Hotel");
    assertThat(hotels.findAll())
        .extracting(Hotel::getAddress)
        .containsExactlyInAnyOrder("10 Central Street, Moscow", "25 River Road, Moscow");
    assertThat(rooms.findAll())
        .hasSize(4)
        .allSatisfy(
            room -> {
              assertThat(room.isAvailable()).isTrue();
              assertThat(room.getTimesBooked())
                  .isEqualTo(
                      switch (room.getNumber()) {
                        case "101", "202" -> 1L;
                        case "201" -> 3L;
                        default -> 0L;
                      });
              assertThat(hotels.existsById(room.getHotel().getId())).isTrue();
            });
    assertThat(rooms.findAll())
        .extracting(room -> room.getHotel().getName() + ":" + room.getNumber())
        .containsExactlyInAnyOrder(
            "Central Hotel:101", "Central Hotel:102", "Riverside Hotel:201", "Riverside Hotel:202");
  }

  /*
   * Исходные данные: При двух демонстрационных отелях ADMIN отправляет Airport Hotel с адресом 1 Airport
   * Road.
   * Ожидаемый результат: Ответ 201 содержит числовой id и переданные реквизиты; после очистки контекста
   * отель читается из базы, всего отелей три.
   */
  @DisplayName("Администратор создаёт отель с сохранением переданных реквизитов")
  @Test
  void adminCreatesHotelAndRowPersistsAlongsideSeeds() throws Exception {
    var response =
        mvc.perform(
                post("/api/hotels")
                    .header("Authorization", bearer("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Airport Hotel\",\"address\":\"1 Airport Road\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("Airport Hotel"))
            .andExpect(jsonPath("$.address").value("1 Airport Road"))
            .andReturn();
    long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    entityManager.flush();
    entityManager.clear();
    Hotel stored = hotels.findById(id).orElseThrow();
    assertThat(stored.getName()).isEqualTo("Airport Hotel");
    assertThat(stored.getAddress()).isEqualTo("1 Airport Road");
    assertThat(hotels.count()).isEqualTo(3);
  }

  /*
   * Исходные данные: ADMIN создаёт номер A-103 в существующем отеле при четырёх демонстрационных номерах.
   * Ожидаемый результат: Ответ 201 содержит id, hotelId, номер, available=true и timesBooked=0; данные и
   * связь сохраняются в базе, всего номеров пять.
   */
  @DisplayName("Администратор создаёт доступный номер с нулевым счётчиком и связью с отелем")
  @Test
  void adminCreatesRoomWithDefaultAvailabilityAndCounterAndPersistedHotelLink() throws Exception {
    Hotel hotel = hotels.findAll().get(0);
    var response =
        mvc.perform(
                post("/api/rooms")
                    .header("Authorization", bearer("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"hotelId\":" + hotel.getId() + ",\"number\":\"A-103\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.hotelId").value(hotel.getId()))
            .andExpect(jsonPath("$.number").value("A-103"))
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.timesBooked").value(0))
            .andReturn();
    long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    entityManager.flush();
    entityManager.clear();
    Room stored = rooms.findById(id).orElseThrow();
    assertThat(stored.getHotel().getId()).isEqualTo(hotel.getId());
    assertThat(stored.getNumber()).isEqualTo("A-103");
    assertThat(stored.isAvailable()).isTrue();
    assertThat(stored.getTimesBooked()).isZero();
    assertThat(rooms.count()).isEqualTo(5);
  }

  /*
   * Исходные данные: ADMIN создаёт номер 101 с hotelId=Long.MAX_VALUE и traceId missing-hotel; исходное
   * число номеров сохранено.
   * Ожидаемый результат: Ответ 404 содержит Hotel not found, путь /api/rooms и исходный traceId; число
   * номеров не меняется.
   */
  @DisplayName("Создание номера неизвестного отеля возвращает 404 без записи")
  @Test
  void unknownHotelReturns404WithoutCreatingRoom() throws Exception {
    long before = rooms.count();
    mvc.perform(
            post("/api/rooms")
                .header("Authorization", bearer("ADMIN"))
                .header("X-Trace-Id", "missing-hotel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hotelId\":9223372036854775807,\"number\":\"101\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value("Not Found"))
        .andExpect(jsonPath("$.message").value("Hotel not found"))
        .andExpect(jsonPath("$.path").value("/api/rooms"))
        .andExpect(jsonPath("$.traceId").value("missing-hotel"));
    assertThat(rooms.count()).isEqualTo(before);
  }

  /*
   * Исходные данные: Параметр path выбирает /api/hotels или /api/rooms и соответствующее корректное тело;
   * запросы выполняются без токена и с ролью USER, traceId=catalog-denied.
   * Ожидаемый результат: Для каждого пути ответы равны 401 и 403 с полным ErrorDTO и исходным traceId;
   * числа отелей и номеров не меняются.
   */
  @DisplayName("Создание отелей и номеров закрыто для USER и анонимного клиента")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"/api/hotels", "/api/rooms"})
  void userAndAnonymousCannotCreateCatalogEntries(String path) throws Exception {
    String body =
        path.endsWith("hotels")
            ? "{\"name\":\"Forbidden Hotel\",\"address\":\"address\"}"
            : "{\"hotelId\":" + hotels.findAll().get(0).getId() + ",\"number\":\"forbidden\"}";
    long hotelCount = hotels.count();
    long roomCount = rooms.count();
    for (boolean authenticated : new boolean[] {false, true}) {
      var request =
          post(path)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body)
              .header("X-Trace-Id", "catalog-denied");
      if (authenticated) request.header("Authorization", bearer("USER"));
      int expected = authenticated ? 403 : 401;
      mvc.perform(request)
          .andExpect(status().is(expected))
          .andExpect(jsonPath("$.status").value(expected))
          .andExpect(jsonPath("$.error").value(authenticated ? "Forbidden" : "Unauthorized"))
          .andExpect(jsonPath("$.timestamp").exists())
          .andExpect(jsonPath("$.message").exists())
          .andExpect(jsonPath("$.path").value(path))
          .andExpect(jsonPath("$.traceId").value("catalog-denied"));
    }
    assertThat(hotels.count()).isEqualTo(hotelCount);
    assertThat(rooms.count()).isEqualTo(roomCount);
  }

  /*
   * Исходные данные: Параметр body из ValueSource задаёт пустой объект либо пробельное имя или адрес;
   * запрос создания выполняет ADMIN.
   * Ожидаемый результат: Каждый набор возвращает 400 с массивом fieldErrors; число отелей остаётся равным
   * двум.
   */
  @DisplayName("Отсутствующие или пробельные поля отеля возвращают 400")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(
      strings = {
        "{}",
        "{\"name\":\" \",\"address\":\"address\"}",
        "{\"name\":\"name\",\"address\":\" \"}"
      })
  void missingOrBlankHotelFieldsReturn400(String body) throws Exception {
    mvc.perform(
            post("/api/hotels")
                .header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.fieldErrors").isArray());
    assertThat(hotels.count()).isEqualTo(2);
  }

  /*
   * Исходные данные: Параметр body из ValueSource задаёт пустой объект, нулевой или отрицательный hotelId
   * либо пробельный номер; запрос выполняет ADMIN.
   * Ожидаемый результат: Каждый набор возвращает 400 с массивом fieldErrors; число номеров остаётся равным
   * четырём.
   */
  @DisplayName("Отсутствующие или некорректные поля номера возвращают 400")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(
      strings = {
        "{}",
        "{\"hotelId\":0,\"number\":\"101\"}",
        "{\"hotelId\":-1,\"number\":\"101\"}",
        "{\"hotelId\":1,\"number\":\" \"}"
      })
  void missingOrInvalidRoomFieldsReturn400(String body) throws Exception {
    mvc.perform(
            post("/api/rooms")
                .header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors").isArray());
    assertThat(rooms.count()).isEqualTo(4);
  }

  /*
   * Исходные данные: Параметр path выбирает /api/hotels или /api/rooms; ADMIN отправляет тело из одной
   * открывающей фигурной скобки.
   * Ожидаемый результат: Каждый путь возвращает 400 с сообщением Invalid request body.
   */
  @DisplayName("Незавершённый JSON при создании каталога возвращает 400")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"/api/hotels", "/api/rooms"})
  void malformedJsonReturns400ErrorDto(String path) throws Exception {
    mvc.perform(
            post(path)
                .header("Authorization", bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value("Invalid request body"));
  }
}
