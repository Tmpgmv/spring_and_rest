package com.example.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Открывает только проверенную тестовую H2 и хранит исходный снимок её схемы и данных. */
public final class GuardedTestDataSource extends AbstractDataSource implements DisposableBean {
  private static final Logger LOG = LoggerFactory.getLogger(GuardedTestDataSource.class);
  private final String url;
  private final Connector connector;
  private final ReentrantLock methodLock = new ReentrantLock();
  private final Set<Connection> borrowed = ConcurrentHashMap.newKeySet();
  private Connection anchor;
  private List<String> baseline;
  private boolean closed;

  @FunctionalInterface
  interface Connector {
    Connection connect(String url) throws SQLException;
  }

  static GuardedTestDataSource create() {
    return new GuardedTestDataSource(
        "jdbc:h2:mem:test_" + UUID.randomUUID().toString().replace("-", ""),
        target -> DriverManager.getConnection(target, "sa", ""));
  }

  GuardedTestDataSource(String url, Connector connector) {
    validateTarget(url);
    this.url = url;
    this.connector = connector;
    LOG.info("Isolated test database: {}", url);
  }

  static void validateTarget(String url) {
    if (url == null || !url.matches("jdbc:h2:mem:test_[a-f0-9]{32}")) {
      throw new IllegalArgumentException("Only generated in-memory H2 test databases are allowed");
    }
  }

  @Override
  public synchronized Connection getConnection() throws SQLException {
    if (closed) throw new SQLException("Test database is closed");
    if (anchor == null) anchor = openVerifiedConnection();
    return track(openVerifiedConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    if (!"sa".equals(username) || !"".equals(password)) {
      throw new SQLException("Test database credentials cannot be overridden");
    }
    return getConnection();
  }

  private Connection openVerifiedConnection() throws SQLException {
    validateTarget(url);
    Connection connection = connector.connect(url);
    try {
      if (!url.equals(connection.getMetaData().getURL())) {
        throw new SQLException("Connection metadata does not match the isolated test database");
      }
      return connection;
    } catch (SQLException | RuntimeException failure) {
      try {
        connection.close();
      } catch (SQLException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  private Connection track(Connection connection) {
    borrowed.add(connection);
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(connection, arguments);
                if (method.getName().equals("close")) borrowed.remove(connection);
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  void beforeMethod() throws SQLException {
    methodLock.lock();
    try {
      requireNoBorrowers();
      restoreBaseline();
    } catch (SQLException | RuntimeException failure) {
      methodLock.unlock();
      throw failure;
    }
  }

  private void restoreBaseline() throws SQLException {
    try (Connection connection = getConnection();
        var statement = connection.createStatement()) {
      if (baseline == null) {
        baseline = new ArrayList<>();
        try (var script = statement.executeQuery("SCRIPT")) {
          while (script.next()) baseline.add(script.getString(1));
        }
      } else {
        statement.execute("DROP ALL OBJECTS");
        for (String sql : baseline) statement.execute(sql);
      }
    }
  }

  void afterMethod() {
    try {
      requireNoBorrowers();
    } finally {
      if (methodLock.isHeldByCurrentThread()) methodLock.unlock();
    }
  }

  private void requireNoBorrowers() {
    if (!borrowed.isEmpty()) {
      throw new IllegalStateException(
          "Outstanding test connections must finish before database reset");
    }
  }

  @Override
  public synchronized void destroy() throws SQLException {
    closed = true;
    SQLException failure = null;
    for (Connection connection : borrowed) {
      try {
        connection.close();
      } catch (SQLException ex) {
        if (failure == null) failure = ex;
        else failure.addSuppressed(ex);
      }
    }
    borrowed.clear();
    if (anchor != null) anchor.close();
    if (failure != null) throw failure;
  }
}
