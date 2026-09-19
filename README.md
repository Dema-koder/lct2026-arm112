# Тренажёр АРМ-112 ДДС

Полноценный учебный контур интерфейса обучающегося: Java/Spring Boot backend, PostgreSQL и адаптированный под них web-интерфейс АРМ ДДС. Frontend и backend реализуют единый контракт [`docs/contracts/openapi.yaml`](docs/contracts/openapi.yaml).

## Стек

- Java 21;
- Spring Boot 4.1.1;
- Spring Web MVC и Bean Validation;
- Spring Security, JWT HS256;
- raw WebSocket с одноразовым ticket;
- PostgreSQL 17, JDBC и Flyway;
- Maven и Docker Compose;
- React 19, TypeScript и Next.js/vinext;
- REST API и WebSocket для обновления карточки в реальном времени.

## Быстрый запуск через Docker Compose

Понадобится Docker Desktop или Docker Engine с Compose. Java, Maven, Node.js и PostgreSQL устанавливать локально не нужно.

Из корня репозитория выполните:

```bash
docker compose up --build
```

После запуска доступны:

- интерфейс: `http://localhost:3000`;
- backend: `http://localhost:8080`;
- Swagger UI: `http://localhost:8080/swagger-ui.html`;
- PostgreSQL: `localhost:55432`.

Откройте интерфейс: `http://localhost:3000`. Войдите с логином `trainee` и паролем `trainee`.

Проверка состояния контейнеров и backend:

```bash
docker compose ps
curl http://localhost:8080/actuator/health
```

Остановка без удаления данных:

```bash
docker compose down
```

Состояние занятия сохранится в Docker volume `postgres_data`. Полный сброс демонстрационных данных выполняется отдельной командой, которая безвозвратно удаляет этот volume:

```bash
docker compose down -v
docker compose up --build
```

Подробные варианты запуска, локальная разработка и диагностика описаны в [`docs/POSTGRES_DOCKER.md`](docs/POSTGRES_DOCKER.md).

Интерактивная документация Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

Swagger использует зафиксированный контракт [`docs/contracts/openapi.yaml`](docs/contracts/openapi.yaml). Перед запросами нажмите **Authorize** и заполните:

- `contractVersion`: `0.2`;
- `bearerAuth`: JWT из ответа `POST /auth/login` без префикса `Bearer` — для защищённых запросов.

Тестовая учётная запись для интерфейса, API и Swagger:

- логин: `trainee`;
- пароль: `trainee`.

Пример входа:

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'X-Contract-Version: 0.2' \
  -d '{"username":"trainee","password":"trainee"}'
```

Полученный `accessToken` передаётся как `Authorization: Bearer <token>`.

## Реализовано

- экран входа в стилистике существующего АРМ-112;
- журнал происшествий с поиском, статусами и таймерами нормативов;
- рабочее окно карточки ДДС с данными заявителя, адресом, описанием и историей;
- панель службы со всеми разрешёнными backend действиями;
- диалоги комментария, исходящего звонка и итоговой оценки;
- восстановление авторизации, обработка ошибок API и realtime-обновления через WebSocket;
- контекст обучающегося и рабочего места;
- назначенная активная учебная сессия `CARD_ACTIONS`;
- очередь готовых карточек ДДС;
- принятие и отказ с серверной state machine;
- нормативы 30 секунд и 3 минуты с событиями просрочки;
- начало реагирования, отказ от работ и завершение;
- симулятор исходящего звонка на 3–4-значный номер;
- обязательность звонка и комментария согласно сценарию;
- идемпотентность команд;
- аудит действий в timeline;
- сохранение состояния занятия, карточек, звонков и оценки в PostgreSQL;
- JWT и одноразовые WebSocket-ticket;
- сохранение realtime-событий и REST replay по `sequence` после перезапуска backend;
- итоговая оценка занятия;
- единый формат ошибок.
- Swagger UI, работающий непосредственно с версионированным OpenAPI-контрактом.

При первом запуске создаётся демонстрационная сессия с одной карточкой. После этого backend загружает её состояние из PostgreSQL. Миграции схемы применяются Flyway автоматически до инициализации учебного сценария. API-контракт и DTO frontend при подключении БД не изменились.

## WebSocket

1. Получить ticket: `POST /api/v1/auth/ws-ticket` с Bearer JWT.
2. Подключиться к `ws://localhost:8080/ws/v1?ticket=<ticket>`.
3. После разрыва запросить пропущенные события через `GET /api/v1/training-sessions/{id}/events?afterSequence=N`.

Ticket одноразовый и действует 30 секунд.

## Проверка

Backend:

```bash
mvn clean verify
```

Интеграционный тест проходит полный путь: вход, ошибка валидации, принятие карточки, проверка идемпотентности, начало реагирования, звонок, завершение карточки, отправка занятия, получение оценки и replay событий.

Frontend:

```bash
cd frontend
npm run lint
npm test
```

Frontend-тест собирает production bundle, проверяет серверный HTML и ключевые точки интеграции с контрактом `0.2`.

## Настройки

| Переменная | Назначение | Значение по умолчанию |
|---|---|---|
| `SERVER_PORT` | HTTP-порт | `8080` |
| `SPRING_DATASOURCE_URL` | JDBC URL PostgreSQL | `jdbc:postgresql://localhost:5432/arm112` |
| `SPRING_DATASOURCE_USERNAME` | пользователь PostgreSQL | `arm112` |
| `SPRING_DATASOURCE_PASSWORD` | пароль PostgreSQL | `arm112` |
| `ARM112_JWT_SECRET` | локальный ключ JWT, заменить вне dev | встроенный dev-ключ |
| `ARM112_ALLOWED_ORIGINS` | origins фронтенда через запятую | `http://localhost:3000,http://localhost:5173` |

Параметры Compose можно переопределить через `.env`; полный пример находится в [`.env.example`](.env.example). В частности, `POSTGRES_PORT`, `BACKEND_PORT` и `FRONTEND_PORT` меняют опубликованные порты хоста.

API-контракт и правила независимой разработки фронтенда и бэкенда находятся в [`docs/contracts/README.md`](docs/contracts/README.md).

Подробная пошаговая инструкция по запуску Swagger, авторизации и прохождению полного учебного сценария: [`docs/frontend/SWAGGER_GUIDE.md`](docs/frontend/SWAGGER_GUIDE.md).

Настройка и команды frontend описаны отдельно в [`frontend/README.md`](frontend/README.md).
