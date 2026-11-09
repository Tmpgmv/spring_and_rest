package com.example.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.example.booking.dto.RegisterRequest;
import com.example.booking.entity.User;
import com.example.booking.entity.UserRole;
import com.example.booking.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
  @Mock UserRepository repository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock JwtTokenService tokens;
  UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(repository, passwordEncoder, tokens);
  }

  /*
   * Исходные данные: Для каждой роли role из UserRole хранилище возвращает alice с хешем hash; пароль
   * secret совпадает, генератор возвращает fresh-token.
   * Ожидаемый результат: Возвращается fresh-token; генератор получает alice и выбранную роль, пользователь
   * не сохраняется.
   */
  @DisplayName("Верные учётные данные выдают токен с сохранённой ролью")
  @ParameterizedTest(name = "[{index}] {arguments}")
  @EnumSource(UserRole.class)
  void validCredentialsIssueTokenWithStoredRole(UserRole role) {
    when(repository.findByUsername("alice"))
        .thenReturn(Optional.of(new User("alice", "hash", role)));
    when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
    when(tokens.issueToken("alice", role)).thenReturn("fresh-token");

    assertThat(service.authenticate(new RegisterRequest("alice", "secret")).token())
        .isEqualTo("fresh-token");

    verify(tokens).issueToken("alice", role);
    verify(repository, never()).save(any());
  }

  /*
   * Исходные данные: Пользователь alice существует с ролью USER; пароль wrong не совпадает с хешем hash.
   * Ожидаемый результат: Возникает ошибка 401 с причиной Invalid credentials; генератор токенов не
   * вызывается.
   */
  @DisplayName("Неверный пароль отклоняется без выдачи токена")
  @Test
  void wrongPasswordRejectsWithoutIssuingToken() {
    when(repository.findByUsername("alice"))
        .thenReturn(Optional.of(new User("alice", "hash", UserRole.USER)));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
    assertRejected("alice", "wrong");
    verifyNoInteractions(tokens);
  }

  /*
   * Исходные данные: Хранилище не содержит пользователя unknown; передан пароль secret.
   * Ожидаемый результат: Возникает ошибка 401 с причиной Invalid credentials; проверка пароля и выдача
   * токена не выполняются.
   */
  @DisplayName("Неизвестный пользователь получает общую ошибку авторизации")
  @Test
  void unknownUsernameRejectsWithSameGenericError() {
    when(repository.findByUsername("unknown")).thenReturn(Optional.empty());
    assertRejected("unknown", "secret");
    verifyNoInteractions(tokens, passwordEncoder);
  }

  private void assertRejected(String username, String password) {
    assertThatThrownBy(() -> service.authenticate(new RegisterRequest(username, password)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode().value()).isEqualTo(401);
              assertThat(ex.getReason()).isEqualTo("Invalid credentials");
            });
  }
}
