package com.example.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;

class TestDatabaseIsolationTest {
  private static final AtomicBoolean SECONDARY_CREATED = new AtomicBoolean();

  private static final class FirstClass {}

  private static final class SecondClass {}

  private AnnotationConfigApplicationContext context(Class<?> owner, String... properties) {
    var context = preparedContext(owner);
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
    context.refresh();
    return context;
  }

  private AnnotationConfigApplicationContext preparedContext(Class<?> owner) {
    var context = new AnnotationConfigApplicationContext();
    context.register(Fixtures.class);
    new IsolatedDatabaseContextCustomizerFactory()
        .createContextCustomizer(owner, List.of())
        .customizeContext(context, null);
    return context;
  }

  private GuardedTestDataSource source(AnnotationConfigApplicationContext context) {
    return context.getBean(GuardedTestDataSource.class);
  }

  private String url(AnnotationConfigApplicationContext context) throws SQLException {
    try (var connection = source(context).getConnection()) {
      return connection.getMetaData().getURL();
    }
  }

  private int count(DataSource source) throws SQLException {
    try (var connection = source.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select count(*) from marker")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private void insert(DataSource source) throws SQLException {
    try (var connection = source.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("insert into marker values (2)");
    }
  }

  /*
   * Исходные данные: два разных класса создают контексты и таблицы с одной исходной строкой.
   * Ожидаемый результат: адреса различаются; вставка во вторую строку первой базы не видна во второй.
   */
  @Test
  @DisplayName("Разные тестовые классы получают независимые базы и ключи кеша контекстов")
  void differentClassesHaveDifferentDatabases() throws Exception {
    var factory = new IsolatedDatabaseContextCustomizerFactory();
    assertThat(factory.createContextCustomizer(FirstClass.class, List.of()))
        .isNotEqualTo(factory.createContextCustomizer(SecondClass.class, List.of()))
        .isEqualTo(factory.createContextCustomizer(FirstClass.class, List.of()));
    try (var first = context(FirstClass.class);
        var second = context(SecondClass.class)) {
      assertThat(url(first)).matches("jdbc:h2:mem:test_[a-f0-9]{32}").isNotEqualTo(url(second));
      insert(source(first));
      assertThat(count(source(first))).isEqualTo(2);
      assertThat(count(source(second))).isEqualTo(1);
    }
  }

  /*
   * Исходные данные: контекст класса закрыт после вставки дополнительной строки, затем создан заново.
   * Ожидаемый результат: новая база имеет другой адрес и только одну исходную строку.
   */
  @Test
  @DisplayName(
      "Повторное создание контекста класса создаёт новую пустую относительно прошлого запуска базу")
  void recreatedContextDoesNotReuseDatabase() throws Exception {
    String previous;
    try (var first = context(FirstClass.class)) {
      previous = url(first);
      insert(source(first));
    }
    try (var second = context(FirstClass.class)) {
      assertThat(url(second)).isNotEqualTo(previous);
      assertThat(count(source(second))).isEqualTo(1);
    }
  }

  /*
   * Исходные данные: каждый параметр задаёт запрещённый JDBC URL; соединение предоставляла бы заглушка.
   * Ожидаемый результат: URL отклоняется до первого обращения к заглушке соединения.
   */
  @ParameterizedTest(name = "Запрещённая цель: {0}")
  @ValueSource(
      strings = {
        "jdbc:h2:mem:hotel",
        "jdbc:h2:mem:booking",
        "jdbc:h2:./test-file",
        "jdbc:h2:tcp://127.0.0.1:9999/mem:test",
        "jdbc:postgresql://127.0.0.1:9999/test",
        "jdbc:h2:mem:test_0123456789abcdef0123456789abcdef;INIT=RUNSCRIPT FROM 'unsafe.sql'"
      })
  @DisplayName("Защита отклоняет обычные, файловые, серверные и внешние адреса до подключения")
  void prohibitedTargetsNeverConnect(String target) {
    var connector = mock(GuardedTestDataSource.Connector.class);
    assertThatThrownBy(() -> new GuardedTestDataSource(target, connector))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("in-memory H2");
    verifyNoInteractions(connector);
  }

  /*
   * Исходные данные: разрешённый адрес, но заглушка соединения сообщает метаданные обычной базы booking.
   * Ожидаемый результат: соединение закрыто, SQL не выполнялся, вызывающий код получает SQLException.
   */
  @Test
  @DisplayName("Несоответствующие метаданные блокируют соединение до SQL-инициализации")
  void metadataIsVerifiedBeforeSql() throws Exception {
    var connection = mock(Connection.class);
    var metadata = mock(DatabaseMetaData.class);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getURL()).thenReturn("jdbc:h2:mem:booking");
    try (var data = closeableSource(connection)) {
      assertThatThrownBy(data::getConnection)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("metadata");
      verify(connection).close();
      verify(connection, never()).createStatement();
    }
  }

  private CloseableSource closeableSource(Connection connection) {
    return new CloseableSource(
        new GuardedTestDataSource(
            "jdbc:h2:mem:test_" + UUID.randomUUID().toString().replace("-", ""),
            target -> connection));
  }

  private record CloseableSource(GuardedTestDataSource source) implements AutoCloseable {
    Connection getConnection() throws SQLException {
      return source.getConnection();
    }

    @Override
    public void close() throws SQLException {
      source.destroy();
    }
  }

  /*
   * Исходные данные: приоритетное тестовое свойство содержит каждый из запрещённых адресов подключения.
   * Ожидаемый результат: инициализируется только сгенерированная H2; исходная строка в ней доступна.
   */
  @ParameterizedTest(name = "Внешнее переопределение: {0}")
  @ValueSource(
      strings = {
        "jdbc:h2:mem:hotel",
        "jdbc:h2:mem:booking",
        "jdbc:h2:./test-file",
        "jdbc:h2:tcp://127.0.0.1:9999/mem:test",
        "jdbc:postgresql://127.0.0.1:9999/test"
      })
  @DisplayName("Тестовые переопределения не перенаправляют источник данных на запрещённую базу")
  void propertiesCannotRedirectConnections(String target) throws Exception {
    try (var context =
        context(
            FirstClass.class,
            "spring.datasource.url=" + target,
            "spring.datasource.hikari.jdbc-url=" + target)) {
      assertThat(url(context)).startsWith("jdbc:h2:mem:test_");
      assertThat(count(source(context))).isEqualTo(1);
    }
  }

  /*
   * Исходные данные: системные свойства и переменные окружения в контексте содержат адреса обычных БД.
   * Ожидаемый результат: фактическое соединение всё равно относится к уникальной тестовой H2.
   */
  @Test
  @DisplayName("Системные свойства и переменные окружения не меняют тестовую цель подключения")
  void systemAndEnvironmentOverridesCannotRedirectConnections() throws Exception {
    try (var context = preparedContext(FirstClass.class)) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "attemptedSystemOverride", Map.of("spring.datasource.url", "jdbc:h2:mem:hotel")));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  "attemptedEnvironmentOverride",
                  Map.of("SPRING_DATASOURCE_URL", "jdbc:h2:mem:booking")));
      context.refresh();
      assertThat(url(context)).startsWith("jdbc:h2:mem:test_");
      assertThat(count(source(context))).isEqualTo(1);
    }
  }

  /*
   * Исходные данные: отдельный контролируемый ресурс содержит строку; свойство пытается указать его URL.
   * Ожидаемый результат: тестовый контекст подключается к другой БД; контрольная строка не изменяется.
   */
  @Test
  @DisplayName(
      "Контролируемая запрещённая база сохраняет данные при попытке перенаправления тестов")
  void sentinelDatabaseRemainsUnchanged() throws Exception {
    String target = "jdbc:h2:mem:sentinel_" + UUID.randomUUID().toString().replace("-", "");
    try (var sentinel = DriverManager.getConnection(target, "sa", "");
        var statement = sentinel.createStatement()) {
      statement.execute("create table sentinel(payload integer)");
      statement.execute("insert into sentinel values (42)");
      try (var context = context(FirstClass.class, "spring.datasource.url=" + target)) {
        insert(source(context));
        assertThat(url(context)).isNotEqualTo(target);
      }
      try (var result = statement.executeQuery("select payload from sentinel")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(42);
        assertThat(result.next()).isFalse();
      }
    }
  }

  /*
   * Исходные данные: дополнительный источник объявлен фабричным методом, который отмечает своё создание.
   * Ожидаемый результат: контекст отклоняется до вызова фабрики и до SQL-инициализации.
   */
  @Test
  @DisplayName("Дополнительный источник данных отклоняется до создания его соединений")
  void additionalDataSourceIsRejectedBeforeInstantiation() {
    SECONDARY_CREATED.set(false);
    try (var context = preparedContext(FirstClass.class)) {
      context.register(ExtraSource.class);
      assertThatThrownBy(context::refresh).hasMessageContaining("Additional test data sources");
      assertThat(SECONDARY_CREATED).isFalse();
    }
  }

  /*
   * Исходные данные: свойство Hibernate задаёт отдельный адрес, обходящий обычный DataSource.
   * Ожидаемый результат: контекст прекращает подготовку до инициализации БД.
   */
  @Test
  @DisplayName("Альтернативный адрес подключения JPA запрещён до инициализации контекста")
  void alternativeJpaConnectionIsRejected() {
    try (var context = preparedContext(FirstClass.class)) {
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          context, "spring.jpa.properties.hibernate.connection.url=jdbc:h2:mem:booking");
      assertThatThrownBy(context::refresh).hasMessageContaining("Alternative JPA connections");
    }
  }

  /*
   * Исходные данные: исходная таблица содержит строку; фоновая задача добавляет вторую и завершается.
   * Ожидаемый результат: следующий метод видит только исходную строку; закрытая база не выдаёт соединения.
   */
  @Test
  @DisplayName("Восстановление снимка удаляет изменения завершённого фонового сценария")
  void methodResetRestoresDataAfterBackgroundWork() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    try (var context = context(FirstClass.class)) {
      var data = source(context);
      data.beforeMethod();
      try {
        executor
            .submit(
                () -> {
                  insert(data);
                  return null;
                })
            .get(5, TimeUnit.SECONDS);
        assertThat(count(data)).isEqualTo(2);
      } finally {
        data.afterMethod();
      }
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      data.beforeMethod();
      try {
        assertThat(count(data)).isEqualTo(1);
      } finally {
        data.afterMethod();
      }
      data.destroy();
      assertThatThrownBy(data::getConnection)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("closed");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /*
   * Исходные данные: два потока одновременно создают контексты разных классов и добавляют строки.
   * Ожидаемый результат: адреса различаются, в каждой базе ровно две собственные строки; потоки завершены.
   */
  @Test
  @DisplayName("Параллельные классы создают разные базы и не видят чужие записи")
  void parallelContextsRemainIndependent() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results =
          executor.invokeAll(
              List.of(
                  () -> parallelContext(FirstClass.class),
                  () -> parallelContext(SecondClass.class)));
      assertThat(results.get(0).get(5, TimeUnit.SECONDS))
          .isNotEqualTo(results.get(1).get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private String parallelContext(Class<?> owner) throws Exception {
    try (var context = context(owner)) {
      insert(source(context));
      assertThat(count(source(context))).isEqualTo(2);
      return url(context);
    }
  }

  /*
   * Исходные данные: первый метод удерживает базу; второй поток пытается начать следующий метод.
   * Ожидаемый результат: второй метод ждёт освобождения блокировки и затем видит только исходную строку.
   */
  @Test
  @DisplayName(
      "Методы одного класса не изменяют общую базу одновременно при внешнем включении параллелизма")
  void methodLockSerializesResetAndExecution() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var entered = new java.util.concurrent.CountDownLatch(1);
    try (var context = context(FirstClass.class)) {
      var data = source(context);
      data.beforeMethod();
      java.util.concurrent.Future<Integer> next;
      try {
        insert(data);
        next =
            executor.submit(
                () -> {
                  entered.countDown();
                  data.beforeMethod();
                  try {
                    return count(data);
                  } finally {
                    data.afterMethod();
                  }
                });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> next.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        data.afterMethod();
      }
      assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /*
   * Исходные данные: конфигурация пытается заменить основной источник данных своим фабричным методом.
   * Ожидаемый результат: Spring сохраняет защищённый источник и не вызывает подменённую фабрику.
   */
  @Test
  @DisplayName("Фабричный метод не заменяет защищённый основной источник данных")
  void factoryMethodCannotReplacePrimaryDataSource() throws Exception {
    SECONDARY_CREATED.set(false);
    try (var context = preparedContext(FirstClass.class)) {
      context.register(ReplacementSource.class);
      context.refresh();
      assertThat(context.getBean("dataSource")).isInstanceOf(GuardedTestDataSource.class);
      assertThat(count(source(context))).isEqualTo(1);
      assertThat(SECONDARY_CREATED).isFalse();
    }
  }

  /*
   * Исходные данные: метод оставляет выданное соединение открытым после завершения операций.
   * Ожидаемый результат: завершение метода отклоняется; закрытие контекста закрывает утёкшее соединение.
   */
  @Test
  @DisplayName(
      "Утёкшее соединение блокирует переход к следующему методу и закрывается вместе с контекстом")
  void outstandingConnectionCannotLeakIntoNextMethod() throws Exception {
    Connection leaked;
    try (var context = context(FirstClass.class)) {
      var data = source(context);
      data.beforeMethod();
      leaked = data.getConnection();
      assertThatThrownBy(data::afterMethod)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Outstanding test connections");
      assertThatThrownBy(data::beforeMethod).isInstanceOf(IllegalStateException.class);
    }
    assertThat(leaked.isClosed()).isTrue();
  }

  /*
   * Исходные данные: определение основного источника заменено до обновления контекста фабрикой заглушки.
   * Ожидаемый результат: проверка отклоняет подмену до создания источника и открытия соединений.
   */
  @Test
  @DisplayName("Прямая замена определения источника данных отклоняется до его создания")
  void replacingBeanDefinitionIsRejected() {
    SECONDARY_CREATED.set(false);
    try (var context = preparedContext(FirstClass.class)) {
      context.registerBean(
          "dataSource",
          DataSource.class,
          () -> {
            SECONDARY_CREATED.set(true);
            return mock(DataSource.class);
          });
      assertThatThrownBy(context::refresh)
          .hasMessageContaining("Replacing the isolated test data source");
      assertThat(SECONDARY_CREATED).isFalse();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    InitializingBean fixture(DataSource data) {
      return () -> {
        try (var connection = data.getConnection();
            var statement = connection.createStatement()) {
          statement.execute("create table marker(id integer)");
          statement.execute("insert into marker values (1)");
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ExtraSource {
    @Bean
    DataSource secondaryDataSource() {
      SECONDARY_CREATED.set(true);
      return mock(DataSource.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ReplacementSource {
    @Bean
    DataSource dataSource() {
      SECONDARY_CREATED.set(true);
      return mock(DataSource.class);
    }
  }
}
