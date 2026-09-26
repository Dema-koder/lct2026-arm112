# Нагрузочная проверка backend

Профиль проверяет одновременно вход, контекст обучающегося, всплеск чтения сессии/карточек/событий,
WebSocket-подключение, ротацию refresh-токена и выход. По умолчанию запускается 20 виртуальных
пользователей; `USERS=50` и `USERS=100` дают два следующих контрольных прогона.

Для корректного теста заранее создайте пользователей `trainee-1` … `trainee-N` с одинаковым
паролем и активные занятия для нужной части пользователей. Один общий логин можно использовать
только для smoke-теста: `USERNAME=trainee`.

```bash
docker run --rm --network host \
  -v "$PWD/load-tests:/scripts:ro" grafana/k6:latest run /scripts/k6-training.js

USERS=100 HOLD=5m USER_PREFIX=trainee- PASSWORD='test-password' \
  k6 run load-tests/k6-training.js
```

Для удалённого стенда задайте `BASE_URL=https://example.ru` и `WS_URL=wss://example.ru`.
Тест считается успешным при доле ошибок ниже 1%, p95 HTTP ниже 750 мс и p99 ниже 1,5 с.
Нагрузочный профиль не запускается автоматически против production из CI.
