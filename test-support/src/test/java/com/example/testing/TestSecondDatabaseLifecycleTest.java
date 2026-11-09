package com.example.testing;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(DatabaseLifecycleFixture.class)
class TestSecondDatabaseLifecycleTest {
  @Autowired DataSource data;

  /*
   * Исходные данные: другой тестовый класс использует ту же конфигурацию Spring и тот же набор данных.
   * Ожидаемый результат: кеш не объединяет базы классов; доступна только одна исходная строка.
   */
  @Test
  @DisplayName("Одинаковые конфигурации разных классов не разделяют Spring-контекст и базу")
  void sameConfigurationStillUsesSeparateDatabase() throws Exception {
    try (var connection = data.getConnection()) {
      String url = connection.getMetaData().getURL();
      assertThat(url).matches("jdbc:h2:mem:test_[a-f0-9]{32}");
      DatabaseLifecycleFixture.CLASS_URLS.put(getClass(), url);
      DatabaseLifecycleFixture.CLASS_URLS.forEach(
          (owner, other) -> {
            if (owner != getClass()) assertThat(other).isNotEqualTo(url);
          });
    }
    assertThat(
            new JdbcTemplate(data)
                .queryForObject("select count(*) from lifecycle_marker", Integer.class))
        .isEqualTo(1);
  }
}
