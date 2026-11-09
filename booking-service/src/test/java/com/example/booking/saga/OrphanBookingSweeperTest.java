package com.example.booking.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.booking.client.HotelClient;
import com.example.booking.entity.*;
import com.example.booking.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"eureka.client.enabled=false", "booking.orphan.sweep-interval=1d"})
class OrphanBookingSweeperTest {
  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
  private static final LocalDate START = LocalDate.of(2030, 2, 1);
  @Autowired BookingRepository bookings;
  @Autowired ProcessedRequestRepository requests;
  @Autowired UserRepository users;
  @Autowired BookingTransactions transactions;
  @Autowired ObjectMapper mapper;
  @MockitoBean HotelClient hotel;
  OrphanBookingSweeper sweeper;

  @BeforeEach
  void setup() {
    requests.deleteAll();
    bookings.deleteAll();
    sweeper =
        new OrphanBookingSweeper(
            requests,
            transactions,
            hotel,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mapper,
            Duration.ofMinutes(5));
  }

  private String stranded(Duration age, Long room) {
    var user = users.save(new User(UUID.randomUUID().toString(), "password", UserRole.USER));
    var booking = bookings.save(new Booking(user, room, START, START.plusDays(2), NOW.minus(age)));
    String id = UUID.randomUUID().toString();
    requests.save(new ProcessedRequest(id, booking, NOW.minus(age)));
    return id;
  }

  private BookingStatus status(String id) {
    return bookings
        .findById(transactions.find(id).orElseThrow().work().bookingId())
        .orElseThrow()
        .getStatus();
  }

  /*
   * Исходные данные: На фиксированный момент 1 января 2030 года бронь номера 12 на 1–3 февраля ожидает
   * подтверждения уже шесть минут; порог равен пяти минутам.
   * Ожидаемый результат: Отмена предшествует освобождению; сохраняется ответ 503 об истечении срока,
   * позднее подтверждение его не меняет, повторная очистка не повторяет освобождение.
   */
  @DisplayName("Просроченная бронь отменяется однократно и не подтверждается позднее")
  @Test
  void strandedBookingConvergesAndReplayAndStaleConfirmationKeepCancellation() {
    String id = stranded(Duration.ofMinutes(6), 12L);
    doAnswer(
            call -> {
              assertThat(status(id)).isEqualTo(BookingStatus.CANCELLED);
              return null;
            })
        .when(hotel)
        .release(12L, START, START.plusDays(2), id);
    sweeper.sweep();
    var outcome = transactions.find(id).orElseThrow().outcome();
    assertThat(outcome.status()).isEqualTo(503);
    assertThat(outcome.body()).contains("Booking expired while processing");
    assertThat(transactions.finish(id, BookingStatus.CONFIRMED, 201, null).outcome())
        .isEqualTo(outcome);
    sweeper.sweep();
    assertThat(status(id)).isEqualTo(BookingStatus.CANCELLED);
    verify(hotel, times(1)).release(12L, START, START.plusDays(2), id);
  }

  /*
   * Исходные данные: Созданы ожидающие брони возрастом четыре и пять минут и подтверждённая бронь возрастом
   * восемь минут; исходный порог равен пяти минутам.
   * Ожидаемый результат: Первая очистка сохраняет состояния без обращения к отелю; после уменьшения порога
   * до трёх минут четырёхминутная бронь отменяется.
   */
  @DisplayName("Очистка учитывает границу срока и пропускает завершённые брони")
  @Test
  void excludesRecentBoundaryAndTerminalBookingsAndHonorsCustomThreshold() {
    String recent = stranded(Duration.ofMinutes(4), 12L);
    String boundary = stranded(Duration.ofMinutes(5), 12L);
    String confirmed = stranded(Duration.ofMinutes(8), 12L);
    transactions.finish(confirmed, BookingStatus.CONFIRMED, 201, null);
    sweeper.sweep();
    assertThat(status(recent)).isEqualTo(BookingStatus.PENDING);
    assertThat(status(boundary)).isEqualTo(BookingStatus.PENDING);
    assertThat(status(confirmed)).isEqualTo(BookingStatus.CONFIRMED);
    verifyNoInteractions(hotel);
    new OrphanBookingSweeper(
            requests,
            transactions,
            hotel,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mapper,
            Duration.ofMinutes(3))
        .sweep();
    assertThat(status(recent)).isEqualTo(BookingStatus.CANCELLED);
  }

  /*
   * Исходные данные: Бронь возрастом шесть минут попала в список просроченных при пороге пять минут, затем
   * была подтверждена.
   * Ожидаемый результат: Повторная проверка не отменяет бронь; состояние остаётся CONFIRMED, обращений к
   * отелю нет.
   */
  @DisplayName("Подтверждение брони защищает её от устаревшего кандидата очистки")
  @Test
  void staleCandidateCannotCancelOrReleaseConcurrentConfirmation() {
    String id = stranded(Duration.ofMinutes(6), 12L);
    var candidates = requests.findOrphanRequestIds(NOW.minusSeconds(300));
    assertThat(candidates).contains(id);
    transactions.finish(id, BookingStatus.CONFIRMED, 201, null);
    assertThat(transactions.cancelOrphan(id, NOW.minusSeconds(300), "expired")).isEmpty();
    assertThat(status(id)).isEqualTo(BookingStatus.CONFIRMED);
    verifyNoInteractions(hotel);
  }

  /*
   * Исходные данные: Просрочены брони номеров 12 и 23 и бронь без выбранного номера; освобождение номера 12
   * выбрасывает исключение.
   * Ожидаемый результат: Все три брони отменены; номер 23 освобождается, всего выполняются два вызова
   * освобождения, для отсутствующего номера вызова нет.
   */
  @DisplayName("Ошибка освобождения не прерывает отмену остальных просроченных броней")
  @Test
  void releaseFailureDoesNotRollBackCancellationOrStopSweepAndNullRoomNeedsNoRelease() {
    String first = stranded(Duration.ofMinutes(7), 12L);
    String second = stranded(Duration.ofMinutes(8), 23L);
    String unselected = stranded(Duration.ofMinutes(9), null);
    doThrow(new IllegalStateException("Hotel unavailable"))
        .when(hotel)
        .release(12L, START, START.plusDays(2), first);
    sweeper.sweep();
    assertThat(status(first)).isEqualTo(BookingStatus.CANCELLED);
    assertThat(status(second)).isEqualTo(BookingStatus.CANCELLED);
    assertThat(status(unselected)).isEqualTo(BookingStatus.CANCELLED);
    verify(hotel).release(23L, START, START.plusDays(2), second);
    verify(hotel, times(2)).release(anyLong(), any(), any(), anyString());
  }
}
