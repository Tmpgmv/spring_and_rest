package com.example.hotel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotel.entity.Hotel;
import com.example.hotel.entity.Room;
import com.example.hotel.repository.HotelRepository;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"eureka.client.enabled=false", "auth.internal.key=test-internal-key"})
@AutoConfigureMockMvc
class InternalRoomReservationTest {
  static final String KEY = "test-internal-key";
  static final LocalDate START = LocalDate.of(2030, 6, 10);
  static final LocalDate END = LocalDate.of(2030, 6, 15);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired HotelRepository hotels;
  @Autowired RoomRepository rooms;
  @Autowired RoomSlotRepository slots;
  @Autowired JdbcTemplate jdbc;
  Hotel hotel;
  Room room;

  @BeforeEach
  void fixture() {
    hotel = hotels.saveAndFlush(new Hotel("Internal fixture", "Internal address"));
    room = rooms.saveAndFlush(new Room(hotel, "101"));
  }

  @AfterEach
  void cleanupCommittedFixtures() {
    jdbc.update(
        "delete from room_slots where room_id in (select id from rooms where hotel_id = ?)",
        hotel.getId());
    jdbc.update("delete from rooms where hotel_id = ?", hotel.getId());
    hotels.deleteById(hotel.getId());
  }

  String body(String requestId, LocalDate start, LocalDate end) throws Exception {
    return mapper.writeValueAsString(
        Map.of("requestId", requestId, "startDate", start.toString(), "endDate", end.toString()));
  }

  MvcResult call(Long roomId, String operation, String requestId, LocalDate start, LocalDate end)
      throws Exception {
    return mvc.perform(
            post("/api/rooms/{id}/{operation}", roomId, operation)
                .header("X-Internal-Key", KEY)
                .contentType("application/json")
                .content(body(requestId, start, end)))
        .andReturn();
  }

  int confirm(Long id, String requestId) throws Exception {
    return call(id, "confirm-availability", requestId, START, END).getResponse().getStatus();
  }

  long countSlots(Long id) {
    return jdbc.queryForObject("select count(*) from room_slots where room_id = ?", Long.class, id);
  }

  long counter(Long id) {
    return rooms.findById(id).orElseThrow().getTimesBooked();
  }

  /*
   * Исходные данные: Для номера 101 выполняются подтверждение, повтор с изменённым выездом и освобождение с
   * requestId=log-request, bookingId=62 и traceId=hop-trace; подключён сборщик событий журнала.
   * Ожидаемый результат: Операции возвращают 200, 409, 200; три записи содержат корреляцию, шаг и исход,
   * конфликт отмечен REJECTED_409, освобождение SUCCESS; MDC после запроса пуст.
   */
  @DisplayName("Журнал резервирования сохраняет корреляцию и очищает MDC")
  @Test
  void sagaLogsCarryCorrelationOnSuccessConflictAndReleaseAndDoNotLeak() throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                com.example.hotel.service.RoomReservationService.class);
    List<Map<String, String>> events = new ArrayList<>();
    var appender =
        new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
          @Override
          protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            events.add(Map.copyOf(event.getMDCPropertyMap()));
            assertThat(event.getFormattedMessage())
                .contains(
                    "bookingId=62",
                    "requestId=log-request",
                    "traceId=hop-trace",
                    "step=",
                    "outcome=");
          }
        };
    appender.start();
    logger.addAppender(appender);
    try {
      for (String operation : List.of("confirm-availability", "confirm-availability", "release")) {
        LocalDate end = events.size() == 1 ? END.plusDays(1) : END;
        mvc.perform(
                post("/api/rooms/{id}/{operation}", room.getId(), operation)
                    .header("X-Internal-Key", KEY)
                    .header("X-Booking-Id", "62")
                    .header("X-Trace-Id", "hop-trace")
                    .contentType("application/json")
                    .content(body("log-request", START, end)))
            .andExpect(status().is(events.size() == 2 ? 409 : 200));
      }
      assertThat(events)
          .hasSize(3)
          .allSatisfy(
              mdc ->
                  assertThat(mdc)
                      .containsEntry("bookingId", "62")
                      .containsEntry("requestId", "log-request")
                      .containsEntry("traceId", "hop-trace")
                      .containsKeys("step", "outcome"));
      assertThat(events.get(1))
          .containsEntry("step", "confirm")
          .containsEntry("outcome", "REJECTED_409");
      assertThat(events.get(2))
          .containsEntry("step", "release")
          .containsEntry("outcome", "SUCCESS");
      assertThat(org.slf4j.MDC.getCopyOfContextMap()).isNullOrEmpty();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /*
   * Исходные данные: Свободный номер 101 подтверждается дважды на 10–15.06.2030 с requestId=replay, затем
   * дважды освобождается и подтверждается с новым requestId.
   * Ожидаемый результат: Все операции возвращают 200; повтор создаёт один слот HELD с датами и временем и
   * счётчик 1; освобождение удаляет слот без уменьшения счётчика; новое подтверждение повышает его до 2.
   */
  @DisplayName("Повторы подтверждения и освобождения идемпотентны и сохраняют счётчик бронирований")
  @Test
  void confirmReplayAndRepeatedReleaseAreIdempotentAndReleaseNeverDecrementsCounter()
      throws Exception {
    assertThat(confirm(room.getId(), "replay")).isEqualTo(200);
    assertThat(confirm(room.getId(), "replay")).isEqualTo(200);
    var slot = slots.findByRequestId("replay").orElseThrow();
    assertThat(slot.getStartDate()).isEqualTo(START);
    assertThat(slot.getEndDate()).isEqualTo(END);
    assertThat(slot.getStatus().name()).isEqualTo("HELD");
    assertThat(slot.getCreatedAt()).isNotNull();
    assertThat(countSlots(room.getId())).isEqualTo(1);
    assertThat(counter(room.getId())).isEqualTo(1);
    for (int i = 0; i < 2; i++) {
      assertThat(call(room.getId(), "release", "replay", START, END).getResponse().getStatus())
          .isEqualTo(200);
    }
    assertThat(countSlots(room.getId())).isZero();
    assertThat(counter(room.getId())).isEqualTo(1);
    assertThat(confirm(room.getId(), "after-release")).isEqualTo(200);
    assertThat(counter(room.getId())).isEqualTo(2);
  }

  /*
   * Исходные данные: Слотов номера 101 нет; освобождение requestId=absent вызывается для него и
   * неизвестного номера Long.MAX_VALUE.
   * Ожидаемый результат: Оба ответа равны 200, счётчик номера остаётся нулевым.
   */
  @DisplayName("Освобождение отсутствующего слота или номера успешно без изменений")
  @Test
  void releaseOfAbsentSlotOrUnknownRoomIsNoOp() throws Exception {
    assertThat(call(room.getId(), "release", "absent", START, END).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(call(Long.MAX_VALUE, "release", "absent", START, END).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(counter(room.getId())).isZero();
  }

  /*
   * Исходные данные: Номер подтверждён на 10–15.06.2030; startDay и endDay из CsvSource задают дни
   * пересекающегося периода с новым requestId=conflict.
   * Ожидаемый результат: Первое подтверждение возвращает 200, конфликт — 409 с таким же статусом в JSON;
   * остаются один слот и счётчик 1.
   */
  @DisplayName("Пересекающееся резервирование возвращает 409 без изменения слота и счётчика")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"9,11", "14,16", "11,14", "9,16", "10,15"})
  void overlapReturns409WithoutChangingSlotOrCounter(int startDay, int endDay) throws Exception {
    assertThat(confirm(room.getId(), "original")).isEqualTo(200);
    var result =
        call(
            room.getId(),
            "confirm-availability",
            "conflict",
            LocalDate.of(2030, 6, startDay),
            LocalDate.of(2030, 6, endDay));
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("status").asInt())
        .isEqualTo(409);
    assertThat(countSlots(room.getId())).isEqualTo(1);
    assertThat(counter(room.getId())).isEqualTo(1);
  }

  /*
   * Исходные данные: Номер подтверждён на 10–15.06.2030; startDay и endDay задают смежный период 5–10 или
   * 15–20 июня с отдельным requestId.
   * Ожидаемый результат: Оба подтверждения возвращают 200, сохраняются два слота и счётчик 2.
   */
  @DisplayName("Смежные полуоткрытые периоды резервируются независимо")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @CsvSource({"5,10", "15,20"})
  void adjacentHalfOpenRangesBothSucceed(int startDay, int endDay) throws Exception {
    assertThat(confirm(room.getId(), "original")).isEqualTo(200);
    assertThat(
            call(
                    room.getId(),
                    "confirm-availability",
                    "adjacent",
                    LocalDate.of(2030, 6, startDay),
                    LocalDate.of(2030, 6, endDay))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(countSlots(room.getId())).isEqualTo(2);
    assertThat(counter(room.getId())).isEqualTo(2);
  }

  /*
   * Исходные данные: Номер 101 помечен недоступным; подтверждения выполняются для него и номера
   * Long.MAX_VALUE с правильным внутренним ключом.
   * Ожидаемый результат: Ремонтный номер возвращает 409 без слотов и увеличения счётчика; неизвестный
   * возвращает 404 с ErrorDTO.
   */
  @DisplayName("Ремонтный номер отклоняет подтверждение, неизвестный возвращает 404")
  @Test
  void unavailableRoomRejectsAndUnknownRoomReturns404() throws Exception {
    room.setAvailable(false);
    rooms.saveAndFlush(room);
    assertThat(confirm(room.getId(), "maintenance")).isEqualTo(409);
    assertThat(countSlots(room.getId())).isZero();
    assertThat(counter(room.getId())).isZero();
    mvc.perform(
            post("/api/rooms/{id}/confirm-availability", Long.MAX_VALUE)
                .header("X-Internal-Key", KEY)
                .contentType("application/json")
                .content(body("unknown", START, END)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  /*
   * Исходные данные: Номер подтверждён на 10–15.06.2030 с requestId=original, затем available изменён на
   * false; повтор передаёт тот же requestId.
   * Ожидаемый результат: Оба подтверждения возвращают 200, счётчик остаётся 1.
   */
  @DisplayName("Повтор подтверждения успешен после перевода номера на ремонт")
  @Test
  void replayIsAcceptedEvenIfRoomHasSinceBeenTakenOutOfService() throws Exception {
    assertThat(confirm(room.getId(), "original")).isEqualTo(200);
    jdbc.update("update rooms set available = false where id = ?", room.getId());
    assertThat(confirm(room.getId(), "original")).isEqualTo(200);
    assertThat(counter(room.getId())).isEqualTo(1);
  }

  /*
   * Исходные данные: Созданы номера 101 и 102; слот первого подтверждён с requestId=original; повторные
   * подтверждения и освобождения меняют номер, даты либо requestId.
   * Ожидаемый результат: Несовпадение номера или дат возвращает 409; освобождение с другим requestId
   * возвращает 200 без удаления исходного слота; остаётся один слот, счётчик второго номера нулевой.
   */
  @DisplayName("Повторный requestId не позволяет менять номер или даты чужого слота")
  @Test
  void reusedRequestIdCannotTargetAnotherRoomOrDatesAndReleaseCannotRemoveAnotherReservation()
      throws Exception {
    Room other = rooms.saveAndFlush(new Room(hotel, "102"));
    assertThat(confirm(room.getId(), "original")).isEqualTo(200);
    assertThat(confirm(other.getId(), "original")).isEqualTo(409);
    assertThat(
            call(room.getId(), "confirm-availability", "original", END, END.plusDays(2))
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(call(other.getId(), "release", "original", START, END).getResponse().getStatus())
        .isEqualTo(409);
    assertThat(
            call(room.getId(), "release", "original", END, END.plusDays(2))
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            call(room.getId(), "release", "different-request", START, END)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(countSlots(room.getId())).isEqualTo(1);
    assertThat(counter(other.getId())).isZero();
  }

  /*
   * Исходные данные: Параметр operation выбирает confirm-availability или release; для каждой операции
   * запросы содержат JWT роли USER или ADMIN без ключа, затем неверный ключ с traceId bad-internal-key.
   * Ожидаемый результат: Без ключа ответы равны 401, неверный ключ даёт 403 с исходным traceId; слотов нет,
   * счётчик остаётся нулевым.
   */
  @DisplayName("Внутренние операции требуют ключ независимо от роли JWT")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"confirm-availability", "release"})
  void internalKeyIsRequiredAndJwtAloneCannotAuthorizeSystemOperations(String operation)
      throws Exception {
    for (String jwtRole : new String[] {"USER", "ADMIN"}) {
      // JWT с допустимой ролью задаётся постпроцессором Spring; внутренняя аутентификация всё равно
      // требует ключ.
      mvc.perform(
              post("/api/rooms/{id}/{operation}", room.getId(), operation)
                  .with(
                      org.springframework.security.test.web.servlet.request
                          .SecurityMockMvcRequestPostProcessors.jwt()
                          .authorities(
                              new org.springframework.security.core.authority
                                  .SimpleGrantedAuthority("ROLE_" + jwtRole)))
                  .contentType("application/json")
                  .content(body("unauthorized", START, END)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value(401));
    }
    mvc.perform(
            post("/api/rooms/{id}/{operation}", room.getId(), operation)
                .header("X-Internal-Key", "wrong")
                .header("X-Trace-Id", "bad-internal-key")
                .contentType("application/json")
                .content(body("wrong-key", START, END)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.traceId").value("bad-internal-key"));
    assertThat(countSlots(room.getId())).isZero();
    assertThat(counter(room.getId())).isZero();
  }

  /*
   * Исходные данные: Запрос GET /api/hotels содержит только правильный X-Internal-Key без JWT.
   * Ожидаемый результат: Ответ равен 401.
   */
  @DisplayName("Внутренний ключ не даёт доступ к пользовательскому каталогу")
  @Test
  void internalKeyCannotAuthenticateUserEndpoints() throws Exception {
    mvc.perform(get("/api/hotels").header("X-Internal-Key", KEY))
        .andExpect(status().isUnauthorized());
  }

  /*
   * Исходные данные: Параметр operation выбирает confirm-availability или release; с правильным ключом
   * передаются пустой объект, равные или обратные даты и пробельный requestId.
   * Ожидаемый результат: Каждый запрос возвращает 400 с массивом fieldErrors; слоты не создаются.
   */
  @DisplayName("Внутренние операции отклоняют отсутствующие поля и некорректные даты")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @ValueSource(strings = {"confirm-availability", "release"})
  void invalidPayloadsReturn400WithFieldErrors(String operation) throws Exception {
    for (String payload :
        new String[] {
          "{}",
          "{\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-10\",\"requestId\":\"bad\"}",
          "{\"startDate\":\"2030-06-15\",\"endDate\":\"2030-06-10\",\"requestId\":\"bad\"}",
          "{\"startDate\":\"2030-06-10\",\"endDate\":\"2030-06-15\",\"requestId\":\" \"}"
        }) {
      mvc.perform(
              post("/api/rooms/{id}/{operation}", room.getId(), operation)
                  .header("X-Internal-Key", KEY)
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors").isArray());
    }
    assertThat(countSlots(room.getId())).isZero();
  }

  List<Integer> race(List<Callable<Integer>> tasks) throws Exception {
    var executor = Executors.newFixedThreadPool(tasks.size());
    var ready = new CountDownLatch(tasks.size());
    var start = new CountDownLatch(1);
    var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
    try {
      for (var task : tasks)
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Race did not start");
                  return task.call();
                }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (var future : futures) statuses.add(future.get(10, TimeUnit.SECONDS));
      return statuses;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /*
   * Исходные данные: Шесть потоков одновременно подтверждают один номер на 10–15.06.2030 с различными
   * requestId.
   * Ожидаемый результат: Ровно один ответ равен 200, пять — 409; сохраняются один слот и счётчик 1.
   */
  @DisplayName("Из шести конкурентных резервирований пересекающегося периода успешно одно")
  @Test
  @Timeout(20)
  void conflictingConcurrentRequestsHaveExactlyOneWinner() throws Exception {
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String id = "conflict-" + i;
      tasks.add(() -> confirm(room.getId(), id));
    }
    assertThat(race(tasks)).containsExactlyInAnyOrder(200, 409, 409, 409, 409, 409);
    assertThat(countSlots(room.getId())).isEqualTo(1);
    assertThat(counter(room.getId())).isEqualTo(1);
  }

  /*
   * Исходные данные: Шесть потоков одновременно подтверждают один номер на 10–15.06.2030 с
   * requestId=duplicate.
   * Ожидаемый результат: Все шесть ответов равны 200; сохраняются один слот и счётчик 1.
   */
  @DisplayName("Шесть конкурентных повторов создают один слот и одно увеличение счётчика")
  @Test
  @Timeout(20)
  void concurrentDuplicateDeliveryInsertsAndIncrementsExactlyOnce() throws Exception {
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) tasks.add(() -> confirm(room.getId(), "duplicate"));
    assertThat(race(tasks)).containsOnly(200).hasSize(6);
    assertThat(countSlots(room.getId())).isEqualTo(1);
    assertThat(counter(room.getId())).isEqualTo(1);
  }

  /*
   * Исходные данные: Шесть потоков подтверждают один номер на последовательные однодневные периоды с
   * 10.06.2030 с различными requestId.
   * Ожидаемый результат: Все шесть ответов равны 200; сохраняются шесть слотов и счётчик 6.
   */
  @DisplayName("Шесть конкурентных непересекающихся периодов успешно резервируются")
  @Test
  @Timeout(20)
  void concurrentNonOverlappingDatesAllSucceed() throws Exception {
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      int offset = i;
      tasks.add(
          () ->
              call(
                      room.getId(),
                      "confirm-availability",
                      "separate-" + offset,
                      START.plusDays(offset),
                      START.plusDays(offset + 1))
                  .getResponse()
                  .getStatus());
    }
    assertThat(race(tasks)).containsOnly(200).hasSize(6);
    assertThat(countSlots(room.getId())).isEqualTo(6);
    assertThat(counter(room.getId())).isEqualTo(6);
  }

  /*
   * Исходные данные: Два потока одновременно подтверждают номера 101 и 102 на 10–15.06.2030 с
   * requestId=global-request.
   * Ожидаемый результат: Ответы равны 200 и 409; суммарно сохранён один слот, сумма счётчиков равна 1.
   */
  @DisplayName(
      "Единый requestId не создаёт два слота при конкурентном резервировании разных номеров")
  @Test
  @Timeout(20)
  void globalRequestIdConstraintPreventsDoubleUseAcrossDifferentRoomLocks() throws Exception {
    Room other = rooms.saveAndFlush(new Room(hotel, "102"));
    assertThat(
            race(
                List.of(
                    () -> confirm(room.getId(), "global-request"),
                    () -> confirm(other.getId(), "global-request"))))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(countSlots(room.getId()) + countSlots(other.getId())).isEqualTo(1);
    assertThat(counter(room.getId()) + counter(other.getId())).isEqualTo(1);
  }
}
