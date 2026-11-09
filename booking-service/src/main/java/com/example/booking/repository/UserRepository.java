package com.example.booking.repository;

import com.example.booking.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Доступ к таблице users сервиса бронирования. {@link #existsByUsername(String)} проверяет дубли
 * при регистрации; {@link #findByUsername(String)} используется для входа и административного
 * управления.
 */
public interface UserRepository extends JpaRepository<User, Long> {

  boolean existsByUsername(String username);

  Optional<User> findByUsername(String username);
}
