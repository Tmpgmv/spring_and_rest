package com.example.testing;

import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Минимальная схема для проверки автоматического подключения и жизненного цикла Spring-тестов. */
@Configuration(proxyBeanMethods = false)
class DatabaseLifecycleFixture {
  static final ConcurrentHashMap<Class<?>, String> CLASS_URLS = new ConcurrentHashMap<>();

  @Bean
  InitializingBean fixture(DataSource data) {
    return () -> {
      try (var connection = data.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("create table lifecycle_marker(id integer)");
        statement.execute("insert into lifecycle_marker values (1)");
      }
    };
  }

  @Bean
  DataSourceTransactionManager transactionManager(DataSource data) {
    return new DataSourceTransactionManager(data);
  }
}
