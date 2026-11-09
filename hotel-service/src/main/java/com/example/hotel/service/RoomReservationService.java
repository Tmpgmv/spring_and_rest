package com.example.hotel.service;

import com.example.hotel.dto.RoomSlotRequest;
import com.example.hotel.entity.RoomSlot;
import com.example.hotel.logging.SagaLog;
import com.example.hotel.repository.RoomRepository;
import com.example.hotel.repository.RoomSlotRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomReservationService {
  private static final Logger LOG = LoggerFactory.getLogger(RoomReservationService.class);
  private final RoomRepository rooms;
  private final RoomSlotRepository slots;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public RoomReservationService(
      RoomRepository rooms,
      RoomSlotRepository slots,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.rooms = rooms;
    this.slots = slots;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public void confirm(Long roomId, RoomSlotRequest request) {
    try (var context = new SagaLog(MDC.get("bookingId"), request.requestId())) {
      try {
        confirmReservation(roomId, request);
        SagaLog.step(LOG, "confirm", "SUCCESS");
      } catch (ResponseStatusException failure) {
        SagaLog.step(LOG, "confirm", "REJECTED_" + failure.getStatusCode().value());
        throw failure;
      } catch (RuntimeException failure) {
        SagaLog.step(LOG, "confirm", "FAILED");
        throw failure;
      }
    }
  }

  private void confirmReservation(Long roomId, RoomSlotRequest request) {
    try {
      transaction.executeWithoutResult(
          status -> {
            var room =
                rooms
                    .findLockedById(roomId)
                    .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
            var existing = slots.findByRequestId(request.requestId());
            if (existing.isPresent()) {
              requireSameRequest(existing.get(), roomId, request);
              return;
            }
            if (!room.isAvailable()
                || slots.hasOverlap(roomId, request.startDate(), request.endDate())) {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Room is unavailable for the requested dates");
            }
            // Сбрасываем вставку уникального request_id до увеличения счётчика; обе записи
            // фиксируются или откатываются вместе.
            slots.saveAndFlush(
                new RoomSlot(
                    room,
                    request.startDate(),
                    request.endDate(),
                    request.requestId(),
                    clock.instant()));
            room.incrementTimesBooked();
          });
    } catch (DataIntegrityViolationException ex) {
      // Конкурирующий запрос другого номера может первым занять глобально уникальный ключ.
      // После отката нашей транзакции читаем его зафиксированный результат в новой транзакции.
      transaction.executeWithoutResult(
          status -> {
            RoomSlot existing = slots.findByRequestId(request.requestId()).orElseThrow(() -> ex);
            requireSameRequest(existing, roomId, request);
          });
    }
  }

  public void release(Long roomId, RoomSlotRequest request) {
    try (var context = new SagaLog(MDC.get("bookingId"), request.requestId())) {
      try {
        releaseReservation(roomId, request);
        SagaLog.step(LOG, "release", "SUCCESS");
      } catch (ResponseStatusException failure) {
        SagaLog.step(LOG, "release", "REJECTED_" + failure.getStatusCode().value());
        throw failure;
      } catch (RuntimeException failure) {
        SagaLog.step(LOG, "release", "FAILED");
        throw failure;
      }
    }
  }

  private void releaseReservation(Long roomId, RoomSlotRequest request) {
    transaction.executeWithoutResult(
        status -> {
          if (rooms.findLockedById(roomId).isEmpty()) return;
          slots
              .findByRequestId(request.requestId())
              .ifPresent(
                  slot -> {
                    requireSameRequest(slot, roomId, request);
                    slots.delete(slot);
                  });
          // timesBooked хранит историческое число бронирований и никогда не уменьшается.
        });
  }

  private static void requireSameRequest(RoomSlot slot, Long roomId, RoomSlotRequest request) {
    if (!slot.getRoom().getId().equals(roomId)
        || !slot.getStartDate().equals(request.startDate())
        || !slot.getEndDate().equals(request.endDate())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Request ID already belongs to a different reservation");
    }
  }
}
