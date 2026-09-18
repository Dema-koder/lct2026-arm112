# Swagger UI: инструкция для фронтенд-разработчика

Эта инструкция позволяет с нуля запустить локальный backend тренажёра АРМ-112 ДДС, открыть Swagger UI, авторизоваться и вручную пройти полный сценарий работы обучающегося.

## 1. Что уже есть в проекте

Backend предоставляет:

- REST API с базовым адресом `http://localhost:8080/api/v1`;
- Swagger UI по адресу `http://localhost:8080/swagger-ui.html`;
- исходный OpenAPI-контракт по адресу `http://localhost:8080/openapi.yaml`;
- WebSocket по адресу `ws://localhost:8080/ws/v1`;
- демонстрационного пользователя и учебную карточку;
- in-memory хранилище: после перезапуска backend возвращается в исходное состояние.

Swagger отображает именно файл [`../contracts/openapi.yaml`](../contracts/openapi.yaml), который является источником истины для фронтенда и бэкенда.

## 2. Требования к компьютеру

Для обычного запуска нужны:

- Git;
- JDK 21;
- Apache Maven 3.6.3 или новее;
- свободный TCP-порт `8080`;
- браузер Chrome, Firefox или Яндекс Браузер.

Проверить установленные версии:

```bash
git --version
java -version
mvn -version
```

В выводе Java должна быть версия `21`. В выводе Maven строка `Java version` также должна указывать Java 21.

### macOS

Если Homebrew уже установлен:

```bash
brew install openjdk@21 maven git
```

После установки откройте новый терминал и ещё раз выполните `java -version` и `mvn -version`.

### Windows

Установите JDK 21, Maven и Git. Затем откройте новый PowerShell и проверьте:

```powershell
java -version
mvn -version
git --version
```

Если команда `mvn` не найдена, добавьте каталог `bin` Maven в переменную `PATH`. Переменная `JAVA_HOME` должна указывать на установленный JDK 21, а не на JRE или старую Java.

### Linux

Установите JDK 21, Maven и Git средствами вашего дистрибутива, затем проверьте версии командами из начала раздела.

## 3. Получение проекта

Для новой копии:

```bash
git clone git@github.com:Dema-koder/lct2026-arm112.git
cd lct2026-arm112
```

Если SSH-доступ к GitHub не настроен:

```bash
git clone https://github.com/Dema-koder/lct2026-arm112.git
cd lct2026-arm112
```

Если репозиторий уже клонирован:

```bash
cd /путь/к/lct2026-arm112
git pull --ff-only
```

Убедитесь, что вы находитесь в корне проекта. Здесь должны находиться файлы `pom.xml`, `README.md` и каталог `src`:

```bash
ls
```

В PowerShell:

```powershell
Get-ChildItem
```

## 4. Запуск backend через Maven

Из корня репозитория выполните:

```bash
mvn spring-boot:run
```

Первый запуск может занять несколько минут: Maven скачивает зависимости. Backend готов, когда в терминале появятся строки, похожие на:

```text
Tomcat started on port 8080 (http)
Started Arm112Application
```

Терминал с backend должен оставаться открытым. Остановка приложения: `Ctrl+C`.

### Проверка, что backend работает

Откройте второй терминал:

```bash
curl http://localhost:8080/actuator/health
```

Ожидаемый ответ:

```json
{"status":"UP"}
```

Также адрес `http://localhost:8080/openapi.yaml` должен вернуть YAML-файл с заголовком:

```yaml
title: ARM-112 DDS Trainee API
```

## 5. Альтернативный запуск через Docker

Если установлены Docker Desktop или Docker Engine:

```bash
docker build -t arm112-backend .
docker run --rm --name arm112-backend -p 8080:8080 arm112-backend
```

После запуска используйте те же адреса Swagger и API. Остановка контейнера в другом терминале:

```bash
docker stop arm112-backend
```

## 6. Открытие Swagger UI

Откройте в браузере:

```text
http://localhost:8080/swagger-ui.html
```

Адрес перенаправит браузер на внутреннюю страницу Swagger UI. Это нормальное поведение.

Вверху страницы должен отображаться заголовок `ARM-112 DDS Trainee API`, версия `0.2.0-draft` и группы методов:

- `Auth`;
- `Trainee`;
- `References`;
- `Training`;
- `Cards`;
- `Calls`;
- `Assessment`.

Если браузер показывает старую версию контракта, выполните жёсткое обновление страницы:

- macOS: `Cmd+Shift+R`;
- Windows/Linux: `Ctrl+Shift+R`.

## 7. Первая авторизация: версия контракта

Backend требует заголовок:

```http
X-Contract-Version: 0.2
```

Swagger передаёт его через схему авторизации `contractVersion`.

1. Нажмите кнопку **Authorize** с иконкой замка в верхней части страницы.
2. Найдите поле `contractVersion (apiKey)`.
3. Введите строго `0.2`.
4. Нажмите **Authorize** рядом с этим полем.
5. Закройте окно кнопкой **Close**.

На этом этапе `bearerAuth` можно оставить пустым: JWT ещё не получен.

Если `contractVersion` не заполнен, backend возвращает HTTP `426`:

```json
{
  "error": {
    "code": "CONTRACT_VERSION_UNSUPPORTED",
    "message": "Поддерживается версия контракта 0.2"
  }
}
```

## 8. Получение JWT

1. Откройте группу `Auth`.
2. Раскройте `POST /auth/login`.
3. Нажмите **Try it out**.
4. Вставьте тело запроса:

```json
{
  "username": "trainee",
  "password": "trainee"
}
```

5. Нажмите **Execute**.
6. Убедитесь, что `Server response` содержит код `200`.
7. В `Response body` найдите поле `accessToken` и скопируйте только его значение без кавычек.

Пример структуры ответа:

```json
{
  "accessToken": "eyJraWQiOi...",
  "expiresAt": "2026-09-18T20:00:00Z",
  "user": {
    "id": "88888888-8888-4888-8888-888888888888",
    "displayName": "Иванов Иван Иванович",
    "role": "TRAINEE"
  }
}
```

Фактический токен будет длиннее примера.

## 9. Вторая авторизация: JWT

1. Снова нажмите **Authorize**.
2. Убедитесь, что в `contractVersion` сохранено значение `0.2`. Если оно исчезло, введите его повторно.
3. Найдите поле `bearerAuth (http, Bearer)`.
4. Вставьте скопированный `accessToken` без кавычек и без слова `Bearer`.
5. Нажмите **Authorize** рядом с `bearerAuth`.
6. Нажмите **Close**.

Теперь Swagger автоматически формирует заголовки:

```http
Authorization: Bearer <accessToken>
X-Contract-Version: 0.2
```

Проверка авторизации:

1. Откройте `GET /auth/me`.
2. Нажмите **Try it out** → **Execute**.
3. Ожидаемый код — `200`, роль — `TRAINEE`.

HTTP `401` означает, что JWT отсутствует, повреждён или истёк. Повторите вход и вставьте новый токен.

## 10. Быстрая проверка данных для фронтенда

### 10.1 Контекст пользователя и рабочего места

Выполните:

```text
GET /trainee/context
```

В ответе должны быть:

- пользователь;
- рабочее место №12;
- `serverTime`;
- активная сессия с режимом `CARD_ACTIONS`.

Идентификатор демонстрационной сессии:

```text
62c0a11f-cfb0-4cff-922f-a56118079602
```

### 10.2 Очередь карточек

Откройте:

```text
GET /cards
```

Нажмите **Try it out** и заполните:

- `sessionId`: `62c0a11f-cfb0-4cff-922f-a56118079602`;
- `limit`: `25`;
- остальные параметры можно оставить пустыми.

После **Execute** должен вернуться массив `items` с демонстрационной карточкой.

Идентификатор карточки:

```text
11b418c1-f5a4-4a91-8be0-7ba970f63a45
```

При первом запуске карточка имеет:

```json
{
  "status": "RECEIVED",
  "allowedActions": ["ACCEPT", "DECLINE"]
}
```

## 11. Полный happy path в Swagger

Команды изменения состояния требуют уникальный заголовок `Idempotency-Key` в формате UUID.

Получить UUID:

```bash
uuidgen
```

Или выполнить в консоли браузера:

```javascript
crypto.randomUUID()
```

Для каждой новой команды используйте новый UUID. Повтор того же запроса допускается с тем же ключом и вернёт прежний результат.

### Шаг 1. Принять карточку

Откройте:

```text
POST /cards/{cardId}/acceptance
```

Параметры:

- `cardId`: `11b418c1-f5a4-4a91-8be0-7ba970f63a45`;
- `Idempotency-Key`: новый UUID.

Тело:

```json
{
  "action": "ACCEPT",
  "reasonCode": null,
  "comment": null,
  "clientOccurredAt": null
}
```

Ожидаемый результат:

```json
{
  "status": "ACCEPTED",
  "allowedActions": ["START_RESPONSE", "REFUSE_WORK"]
}
```

После принятия в `sla.processingDeadlineAt` появляется срок обработки — три минуты от серверного времени принятия.

### Шаг 2. Начать реагирование

Откройте:

```text
POST /cards/{cardId}/reaction-events
```

Используйте тот же `cardId`, но новый `Idempotency-Key`.

Тело:

```json
{
  "action": "START_RESPONSE",
  "reasonCode": null,
  "comment": "Начато реагирование",
  "clientOccurredAt": null
}
```

Ожидаемый статус карточки:

```text
RESPONSE_STARTED
```

### Шаг 3. Начать исходящий звонок

Откройте:

```text
POST /cards/{cardId}/outbound-calls
```

Укажите `cardId`, новый `Idempotency-Key` и тело:

```json
{
  "shortNumber": "1102"
}
```

Ожидаемый HTTP-код — `201`. Скопируйте `id` созданного звонка. Начальное состояние:

```text
DIALING
```

Симулятор автоматически переводит звонок:

```text
DIALING → RINGING → CONNECTED → ACKNOWLEDGED
```

Переход занимает примерно 1,2 секунды.

### Шаг 4. Проверить состояние звонка

Через две секунды выполните:

```text
GET /outbound-calls/{callId}
```

Подставьте `id` из предыдущего ответа. Ожидаемое состояние:

```text
ACKNOWLEDGED
```

### Шаг 5. Завершить звонок

Выполните:

```text
POST /outbound-calls/{callId}/end
```

Укажите `callId` и новый `Idempotency-Key`. Тело запроса отсутствует. Ожидаемое состояние:

```text
ENDED
```

### Шаг 6. Завершить работы по карточке

Снова откройте:

```text
POST /cards/{cardId}/reaction-events
```

Укажите новый `Idempotency-Key` и тело:

```json
{
  "action": "COMPLETE",
  "reasonCode": null,
  "comment": "Информация передана, работы завершены",
  "clientOccurredAt": null
}
```

Ожидаемый статус:

```text
COMPLETED
```

Поле `allowedActions` после завершения должно быть пустым.

### Шаг 7. Завершить занятие

Откройте:

```text
POST /training-sessions/{sessionId}/submit
```

Укажите:

- `sessionId`: `62c0a11f-cfb0-4cff-922f-a56118079602`;
- новый `Idempotency-Key`.

Тело отсутствует. Ожидаемый HTTP-код — `202`. Скопируйте `assessmentId`.

### Шаг 8. Получить оценку

Откройте:

```text
GET /assessments/{assessmentId}
```

Подставьте полученный `assessmentId`. В демонстрационном сценарии оценка рассчитывается сразу, поэтому ожидается:

```json
{
  "state": "COMPLETED",
  "totalScore": 100.0
}
```

Если карточка была принята позже 30 секунд, балл может быть ниже, а в `issues` появится `ACCEPTANCE_OVERDUE`.

## 12. Проверка веток отказа

После перезапуска backend карточка снова будет в состоянии `RECEIVED`.

### Не принять карточку

Для `POST /cards/{cardId}/acceptance`:

```json
{
  "action": "DECLINE",
  "reasonCode": "WRONG_RECIPIENT",
  "comment": "Карточка направлена ошибочно",
  "clientOccurredAt": null
}
```

Результат: `NOT_ACCEPTED`.

Если отправить `DECLINE` без `reasonCode` и `comment`, backend вернёт `422 VALIDATION_ERROR`.

### Отказаться от выполнения работ

Сначала примите карточку. Затем отправьте в `POST /cards/{cardId}/reaction-events`:

```json
{
  "action": "REFUSE_WORK",
  "reasonCode": "NO_RESOURCES",
  "comment": "Нет доступных сил и средств",
  "clientOccurredAt": null
}
```

Результат: `WORK_REFUSED`.

## 13. Сброс учебного состояния

Данные хранятся в памяти. Чтобы вернуть карточку в исходное состояние:

1. Остановите backend: `Ctrl+C`.
2. Запустите снова:

```bash
mvn spring-boot:run
```

После перезапуска:

- сессия снова `ACTIVE`;
- карточка снова `RECEIVED`;
- история действий и звонков очищена;
- старый JWT использовать не рекомендуется — выполните вход заново.

## 14. Использование API из фронтенда

Базовые настройки клиента:

```typescript
export const apiConfig = {
  baseUrl: 'http://localhost:8080/api/v1',
  contractVersion: '0.2',
  wsUrl: 'ws://localhost:8080/ws/v1'
};
```

Обязательные заголовки защищённого запроса:

```typescript
const headers = {
  Authorization: `Bearer ${accessToken}`,
  'X-Contract-Version': '0.2',
  'Content-Type': 'application/json'
};
```

Для команд изменения состояния добавляется:

```typescript
headers['Idempotency-Key'] = crypto.randomUUID();
```

Один пользовательский клик должен иметь один `Idempotency-Key`. При сетевом retry фронтенд обязан повторить тот же ключ, а не создать новый.

### CORS

По умолчанию разрешены:

```text
http://localhost:3000
http://localhost:5173
```

Это стандартные адреса dev-серверов React/Next.js и Vite. Для другого порта задайте origins перед запуском.

macOS/Linux:

```bash
ARM112_ALLOWED_ORIGINS=http://localhost:4200,http://localhost:5173 mvn spring-boot:run
```

PowerShell:

```powershell
$env:ARM112_ALLOWED_ORIGINS="http://localhost:4200,http://localhost:5173"
mvn spring-boot:run
```

Origin должен совпадать полностью, включая протокол и порт. Завершающий `/` не нужен.

## 15. Проверка WebSocket

Swagger UI проверяет REST, но не предоставляет удобный интерфейс для raw WebSocket.

Сначала через Swagger выполните:

```text
POST /auth/ws-ticket
```

Скопируйте `ticket`. Он одноразовый и действует 30 секунд. Затем в консоли браузера выполните:

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/v1?ticket=ВСТАВЬТЕ_TICKET');
ws.onopen = () => console.log('WS connected');
ws.onmessage = event => console.log('WS event', JSON.parse(event.data));
ws.onerror = error => console.error('WS error', error);
ws.onclose = event => console.log('WS closed', event.code, event.reason);
```

После подключения выполните через Swagger действие с карточкой. В консоли браузера должно появиться событие `card.updated` или `outbound_call.updated`.

После разрыва фронтенд получает пропущенные события через:

```text
GET /training-sessions/{sessionId}/events?afterSequence=N
```

## 16. Основные HTTP-ошибки

| HTTP | Код | Что проверить |
|---|---|---|
| `401` | `UNAUTHORIZED` или стандартная ошибка Security | JWT отсутствует, неверен или истёк |
| `404` | `NOT_FOUND` | неверный `sessionId`, `cardId`, `callId` или `assessmentId` |
| `409` | `INVALID_STATE_TRANSITION` | действие не разрешено в текущем статусе |
| `409` | `IDEMPOTENCY_CONFLICT` | тот же ключ использован с другим телом |
| `409` | `CALL_ALREADY_ACTIVE` | уже есть незавершённый звонок по карточке |
| `422` | `VALIDATION_ERROR` | не заполнена причина, комментарий или обязательный звонок |
| `422` | `CALL_TARGET_NOT_ALLOWED` | короткий номер отсутствует в `callTargets` карточки |
| `426` | `CONTRACT_VERSION_UNSUPPORTED` | не задано точное значение `X-Contract-Version: 0.2` |

Для отображения кнопок фронтенд использует `allowedActions` из ответа карточки. Не следует самостоятельно вычислять разрешённые переходы только по `status`.

## 17. Частые проблемы запуска

### Swagger не открывается

Проверьте health:

```bash
curl http://localhost:8080/actuator/health
```

Если соединение не устанавливается, backend не запущен или запущен на другом порту.

### Порт 8080 занят

macOS/Linux:

```bash
SERVER_PORT=8081 mvn spring-boot:run
```

PowerShell:

```powershell
$env:SERVER_PORT="8081"
mvn spring-boot:run
```

Тогда Swagger будет доступен на `http://localhost:8081/swagger-ui.html`, а frontend должен использовать `http://localhost:8081/api/v1`.

### Maven использует неправильную Java

Выполните:

```bash
mvn -version
```

Если там указана не Java 21, исправьте `JAVA_HOME` и откройте новый терминал.

### Все команды возвращают 426

Откройте **Authorize** и повторно задайте `contractVersion = 0.2`. После обновления Swagger UI сохранённое значение могло очиститься.

### Защищённые методы возвращают 401

Получите новый токен через `POST /auth/login`, откройте **Authorize** и вставьте его в `bearerAuth` без `Bearer`.

### Действие возвращает 409

Запросите актуальную карточку через `GET /cards/{cardId}` и проверьте `status` и `allowedActions`. Если нужен новый чистый сценарий, перезапустите backend.

### Фронтенд получает CORS error

Проверьте точный origin фронтенда и добавьте его в `ARM112_ALLOWED_ORIGINS`. После изменения переменной backend необходимо перезапустить.

## 18. Автоматическая проверка backend

Перед началом интеграции или после обновления ветки выполните:

```bash
mvn clean verify
```

Успешный результат заканчивается строкой:

```text
BUILD SUCCESS
```

Интеграционный тест проверяет:

- доступность Swagger;
- загрузку исходного OpenAPI;
- вход;
- валидацию отказа;
- принятие карточки;
- идемпотентный повтор;
- начало реагирования;
- исходящий звонок;
- завершение работ;
- отправку занятия;
- получение оценки;
- replay событий.

После прохождения `mvn clean verify` backend готов для локальной интеграции фронтенда.
