# Тренажёр АРМ-112 ДДС

Полноценный учебный контур интерфейса обучающегося: Java/Spring Boot backend и адаптированный под него web-интерфейс АРМ ДДС. Оба приложения реализуют единый контракт [`docs/contracts/openapi.yaml`](docs/contracts/openapi.yaml) и запускаются локально без внешних сервисов.

## Стек

- Java 21;
- Spring Boot 4.1.1;
- Spring Web MVC и Bean Validation;
- Spring Security, JWT HS256;
- raw WebSocket с одноразовым ticket;
- Maven;
- in-memory учебное хранилище для MVP.
- React 19, TypeScript и Next.js/vinext;
- REST API и WebSocket для обновления карточки в реальном времени.

## Быстрый запуск всего приложения

Потребуются Java 21, Maven, Node.js 22.13+ и npm.

В первом терминале из корня репозитория запустите backend:

```bash
mvn spring-boot:run
```

Во втором терминале запустите frontend:

```bash
cd frontend
npm install
npm run dev
```

Откройте интерфейс: `http://localhost:3000`. Войдите с логином `trainee` и паролем `trainee`.

Backend будет доступен на `http://localhost:8080`. Проверка состояния:

```bash
curl http://localhost:8080/actuator/health
```

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
- JWT и одноразовые WebSocket-ticket;
- realtime-события и REST replay по `sequence`;
- итоговая оценка занятия;
- единый формат ошибок.
- Swagger UI, работающий непосредственно с версионированным OpenAPI-контрактом.

При запуске создаётся демонстрационная сессия с одной карточкой. Данные хранятся в памяти и сбрасываются после перезапуска — это сознательное ограничение первой интеграционной версии. Следующий инфраструктурный шаг — подключение PostgreSQL и миграций, не меняющее DTO фронтенда.

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
| `ARM112_JWT_SECRET` | локальный ключ JWT, заменить вне dev | встроенный dev-ключ |
| `ARM112_ALLOWED_ORIGINS` | origins фронтенда через запятую | `http://localhost:3000,http://localhost:5173` |

API-контракт и правила независимой разработки фронтенда и бэкенда находятся в [`docs/contracts/README.md`](docs/contracts/README.md).

Подробная пошаговая инструкция по запуску Swagger, авторизации и прохождению полного учебного сценария: [`docs/frontend/SWAGGER_GUIDE.md`](docs/frontend/SWAGGER_GUIDE.md).

Настройка и команды frontend описаны отдельно в [`frontend/README.md`](frontend/README.md).
