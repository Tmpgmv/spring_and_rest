package com.example.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Учётная запись собственной таблицы users сервиса бронирования: id создаётся базой, username
 * уникально, password содержит хеш bcrypt вместо открытого пароля, role хранится как имя {@link
 * UserRole}. Уникальное ограничение username окончательно защищает от дублей: предварительная
 * проверка даёт понятный ответ 409, а база обеспечивает защиту при гонке запросов.
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "username", nullable = false, length = 100)
  private String username;

  /** Хеш bcrypt: открытый пароль никогда не попадает в этот столбец. */
  @Column(name = "password", nullable = false, length = 100)
  private String password;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 10)
  private UserRole role;

  protected User() {
    // Конструктор для JPA.
  }

  public User(String username, String password, UserRole role) {
    this.username = username;
    this.password = password;
    this.role = role;
  }

  public void updateCredentials(String encodedPassword, UserRole role) {
    if (encodedPassword != null) {
      this.password = encodedPassword;
    }
    if (role != null) {
      this.role = role;
    }
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public UserRole getRole() {
    return role;
  }
}
