package com.example.testing;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

@SpringJUnitConfig(DatabaseLifecycleFixture.class)
class TestDatabaseLifecycleTest {
  @Autowired DataSource data;

  /*
   * Исходные данные: Spring автоматически подключает общую инфраструктуру; каждый повтор добавляет строку.
   * Ожидаемый результат: каждый из трёх повторов начинает с одной исходной строки, адрес остаётся тестовым.
   */
  @RepeatedTest(
      value = 3,
      name = "Восстановление перед повтором {currentRepetition} из {totalRepetitions}")
  @DisplayName(
      "Автоматический слушатель восстанавливает зафиксированные изменения перед каждым методом")
  void committedChangesAreReset() throws Exception {
    try (var connection = data.getConnection()) {
      String url = connection.getMetaData().getURL();
      assertThat(url).matches("jdbc:h2:mem:test_[a-f0-9]{32}");
      DatabaseLifecycleFixture.CLASS_URLS.put(getClass(), url);
      DatabaseLifecycleFixture.CLASS_URLS.forEach(
          (owner, other) -> {
            if (owner != getClass()) assertThat(other).isNotEqualTo(url);
          });
    }
    var jdbc = new JdbcTemplate(data);
    assertThat(jdbc.queryForObject("select count(*) from lifecycle_marker", Integer.class))
        .isEqualTo(1);
    jdbc.update("insert into lifecycle_marker values (2)");
    assertThat(jdbc.queryForObject("select count(*) from lifecycle_marker", Integer.class))
        .isEqualTo(2);
  }

  /*
   * Исходные данные: каждый повтор открывает тестовую транзакцию после восстановления исходной строки.
   * Ожидаемый результат: изменение видно внутри транзакции и откатывается без конфликта с восстановлением.
   */
  @RepeatedTest(value = 3, name = "Откат повторения {currentRepetition} из {totalRepetitions}")
  @Transactional
  @DisplayName("Восстановление базы выполняется до открытия транзакции тестового метода")
  void transactionalChangesAreRolledBack() {
    var jdbc = new JdbcTemplate(data);
    assertThat(jdbc.queryForObject("select count(*) from lifecycle_marker", Integer.class))
        .isEqualTo(1);
    jdbc.update("insert into lifecycle_marker values (2)");
    assertThat(jdbc.queryForObject("select count(*) from lifecycle_marker", Integer.class))
        .isEqualTo(2);
  }
}
