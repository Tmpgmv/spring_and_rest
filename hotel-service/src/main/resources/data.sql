-- Загружается после создания схемы Hibernate (defer-datasource-initialization=true).
-- Генерируемые идентификаторы сохраняют готовность последовательностей для последующих вставок через API.
INSERT INTO hotels (name, address) VALUES
  ('Central Hotel', '10 Central Street, Moscow'),
  ('Riverside Hotel', '25 River Road, Moscow');

INSERT INTO rooms (hotel_id, number, available, times_booked) VALUES
  ((SELECT id FROM hotels WHERE name = 'Central Hotel'), '101', TRUE, 1),
  ((SELECT id FROM hotels WHERE name = 'Central Hotel'), '102', TRUE, 0),
  ((SELECT id FROM hotels WHERE name = 'Riverside Hotel'), '201', TRUE, 3),
  ((SELECT id FROM hotels WHERE name = 'Riverside Hotel'), '202', TRUE, 1);

-- Соответствует примеру CONFIRMED сервиса бронирования (номер 1).
INSERT INTO room_slots (room_id, start_date, end_date, request_id, status, created_at) VALUES
  ((SELECT id FROM rooms WHERE hotel_id = (SELECT id FROM hotels WHERE name = 'Central Hotel')
      AND number = '101'), DATE '2030-06-01', DATE '2030-06-04', 'demo-confirmed-001',
   'HELD', TIMESTAMP WITH TIME ZONE '2030-01-01 10:00:00+00');

-- Для отменённого бронирования Riverside 202 слота нет; исторический счётчик остаётся равным 1.
-- У Riverside 201 три исторических бронирования; освобождение никогда не уменьшает счётчики.
-- Вне занятых дат рекомендации следуют в порядке идентификаторов 2, 1, 4, 3:
-- счётчики 0, 1, 1, 3; при равенстве используется идентификатор номера.
