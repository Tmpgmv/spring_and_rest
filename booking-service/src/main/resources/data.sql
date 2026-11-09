-- Демонстрационные учётные записи ADMIN и USER загружаются после
-- создания схемы Hibernate (spring.jpa.defer-datasource-initialization=true).
-- Хеши bcrypt с трудоёмкостью 10 созданы BCryptPasswordEncoder сервиса:
-- admin → admin123 (ADMIN), user → user123 (USER).
-- Учётные данные описаны в README; открытые пароли не сохраняются.
INSERT INTO users (username, password, role) VALUES
  ('admin', '$2a$10$FQkqxHlJX/CGlCmsBrEJMehD/7P.HP1OHS1hWVyFyrOTL1if8Mx1e', 'ADMIN');
INSERT INTO users (username, password, role) VALUES
  ('user', '$2a$10$4ZN8LZQ4MA2aBiV28q0cquQTvtaRJthRUOMw1WiS95Rys6k.Dd88O', 'USER');

-- Фиксированные даты и генерируемые идентификаторы делают примеры после запуска воспроизводимыми.
-- Идентификаторы совпадают с порядком вставки сервиса отелей: Central 101=1, Riverside 202=4.
INSERT INTO bookings (user_id, room_id, start_date, end_date, status, created_at) VALUES
  ((SELECT id FROM users WHERE username = 'user'), 1, DATE '2030-06-01', DATE '2030-06-04',
   'CONFIRMED', TIMESTAMP WITH TIME ZONE '2030-01-01 10:00:00+00'),
  ((SELECT id FROM users WHERE username = 'user'), 4, DATE '2030-06-10', DATE '2030-06-12',
   'CANCELLED', TIMESTAMP WITH TIME ZONE '2030-01-01 11:00:00+00');

-- Сохраняем результаты создания для повторов и идентификатор запроса для отмены.
-- Отменённый пример был успешно подтверждён до отмены пользователем.
INSERT INTO processed_requests (request_id, booking_id, http_status, response_snapshot, processed_at)
SELECT CASE status WHEN 'CONFIRMED' THEN 'demo-confirmed-001' ELSE 'demo-cancelled-001' END,
       id, 201,
       '{"id":' || id || ',"roomId":' || room_id || ',"startDate":"' || start_date
       || '","endDate":"' || end_date || '","status":"CONFIRMED","createdAt":"'
       || CASE status WHEN 'CONFIRMED' THEN '2030-01-01T10:00:00Z' ELSE '2030-01-01T11:00:00Z' END
       || '","requestId":"'
       || CASE status WHEN 'CONFIRMED' THEN 'demo-confirmed-001' ELSE 'demo-cancelled-001' END || '"}',
       created_at
FROM bookings;
