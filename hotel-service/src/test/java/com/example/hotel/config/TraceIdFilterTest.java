package com.example.hotel.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Проверяет контракт traceId целевого сервиса: X-Trace-Id от шлюза попадает в MDC на время запроса.
 * Без заголовка журналируется пустое значение без локальной генерации. После запроса MDC очищается,
 * чтобы контекст не попал в следующий запрос рабочего потока. LoggingEvent читает MDC лениво из
 * вызывающего потока, поэтому обработчик синхронно копирует getMDCPropertyMap() внутри append, до
 * MDC.remove в блоке finally фильтра.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@org.springframework.security.test.context.support.WithMockUser
@AutoConfigureMockMvc
class TraceIdFilterTest {

  private static final List<Map<String, String>> CAPTURED_MDC_SNAPSHOTS =
      new CopyOnWriteArrayList<>();

  private static MdcSnapshotAppender mdcAppender;
  private static Logger traceIdFilterLogger;

  @Autowired private MockMvc mockMvc;

  @AfterAll
  static void detachAppender() {
    if (traceIdFilterLogger != null && mdcAppender != null) {
      traceIdFilterLogger.detachAppender(mdcAppender);
      mdcAppender.stop();
    }
  }

  @BeforeEach
  void attachAppenderAndClearSnapshots() {
    CAPTURED_MDC_SNAPSHOTS.clear();

    // Перенастройка журналирования Boot при загрузке контекста удаляет ранее подключённые
    // обработчики,
    // поэтому подключаем их после внедрения; предварительное отключение обеспечивает
    // идемпотентность между тестами.
    if (mdcAppender == null) {
      mdcAppender = new MdcSnapshotAppender();
      mdcAppender.start();
    }
    traceIdFilterLogger = (Logger) LoggerFactory.getLogger(TraceIdFilter.class);
    traceIdFilterLogger.detachAppender(mdcAppender);
    traceIdFilterLogger.addAppender(mdcAppender);
  }

  /*
   * Исходные данные: Подключён сборщик снимков MDC; аутентифицированный клиент запрашивает неизвестный путь
   * с X-Trace-Id=svc-123.
   * Ожидаемый результат: Ответ равен 404; единственная запись журнала содержит traceId=svc-123.
   */
  @DisplayName("Заголовок traceId попадает в MDC на время запроса")
  @Test
  void headerIsCopiedIntoMdcTraceIdForTheRequest() throws Exception {
    // Путь не соответствует контроллеру и возвращает 404, но фильтр всё равно выполняется.
    mockMvc
        .perform(get("/no-such-path").header(TraceIdFilter.TRACE_HEADER, "svc-123"))
        .andExpect(status().isNotFound());

    assertThat(CAPTURED_MDC_SNAPSHOTS)
        .singleElement()
        .satisfies(mdc -> assertThat(mdc).containsEntry(TraceIdFilter.MDC_KEY, "svc-123"));
  }

  /*
   * Исходные данные: Подключён сборщик снимков MDC; аутентифицированный клиент запрашивает неизвестный путь
   * без X-Trace-Id.
   * Ожидаемый результат: Ответ равен 404; единственная запись содержит пустой traceId без локальной
   * генерации.
   */
  @DisplayName("Без заголовка журнал содержит пустой traceId")
  @Test
  void absentHeaderLogsBlankTraceIdWithoutLocalGeneration() throws Exception {
    mockMvc.perform(get("/no-such-path")).andExpect(status().isNotFound());

    assertThat(CAPTURED_MDC_SNAPSHOTS)
        .singleElement()
        .satisfies(mdc -> assertThat(mdc).containsEntry(TraceIdFilter.MDC_KEY, ""));
  }

  /*
   * Исходные данные: Аутентифицированный клиент запрашивает неизвестный путь с X-Trace-Id=svc-456; MockMvc
   * выполняет фильтр в текущем потоке.
   * Ожидаемый результат: Ответ равен 404; значение traceId в MDC текущего потока равно null.
   */
  @DisplayName("После обработки запроса traceId удаляется из MDC")
  @Test
  void mdcIsClearedAfterTheRequest() throws Exception {
    mockMvc
        .perform(get("/no-such-path").header(TraceIdFilter.TRACE_HEADER, "svc-456"))
        .andExpect(status().isNotFound());

    // MockMvc выполняет фильтр в этом потоке; блок finally должен удалить ключ.
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  /** Синхронно копирует карту MDC при добавлении записи, до MDC.remove фильтра. */
  static class MdcSnapshotAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
      CAPTURED_MDC_SNAPSHOTS.add(Map.copyOf(event.getMDCPropertyMap()));
    }
  }
}
