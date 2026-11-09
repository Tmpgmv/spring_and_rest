package com.example.booking.service;

import com.example.booking.dto.CreateUserRequest;
import com.example.booking.dto.RegisterRequest;
import com.example.booking.dto.TokenResponse;
import com.example.booking.dto.UpdateUserRequest;
import com.example.booking.dto.UserResponse;
import com.example.booking.entity.User;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Регистрация: отклоняет повторное имя с 409, хеширует пароль bcrypt, сохраняет запись и возвращает
 * новый токен. Дубли проверяются предварительно через {@link UserRepository#existsByUsername};
 * перехват {@link DataIntegrityViolationException} защищает от гонок благодаря уникальному
 * ограничению БД. Оба случая дают 409 через ResponseStatusException, который GlobalExceptionHandler
 * преобразует в общий ErrorDTO.
 */
@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;

  public UserService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
  }

  @Transactional
  public TokenResponse register(RegisterRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      throw conflict();
    }

    User user =
        new User(request.username(), passwordEncoder.encode(request.password()), UserRole.USER);
    try {
      userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
      // Защита уникального ограничения: конкурентная регистрация прошла предварительную проверку.
      throw conflict();
    }

    return new TokenResponse(jwtTokenService.issueToken(user.getUsername(), user.getRole()));
  }

  @Transactional
  public UserResponse create(CreateUserRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      throw conflict();
    }
    User user =
        new User(request.username(), passwordEncoder.encode(request.password()), request.role());
    try {
      userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
      throw conflict();
    }
    return UserResponse.from(user);
  }

  @Transactional
  public UserResponse update(UpdateUserRequest request) {
    User user = findUser(request.username());
    user.updateCredentials(
        request.password() == null ? null : passwordEncoder.encode(request.password()),
        request.role());
    return UserResponse.from(user);
  }

  @Transactional
  public void delete(String username) {
    userRepository.delete(findUser(username));
  }

  private User findUser(String username) {
    return userRepository
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatusCode.valueOf(404), "User not found"));
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatusCode.valueOf(409), "Username already exists");
  }

  @Transactional(readOnly = true)
  public TokenResponse authenticate(RegisterRequest request) {
    User user =
        userRepository
            .findByUsername(request.username())
            .orElseThrow(UserService::invalidCredentials);
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
      throw invalidCredentials();
    }
    return new TokenResponse(jwtTokenService.issueToken(user.getUsername(), user.getRole()));
  }

  private static ResponseStatusException invalidCredentials() {
    return new ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid credentials");
  }
}
