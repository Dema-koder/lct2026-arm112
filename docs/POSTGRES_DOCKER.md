# PostgreSQL и запуск через Docker Compose

Эта инструкция запускает весь учебный контур одной командой: PostgreSQL, Java/Spring backend и web-интерфейс обучающегося.

## 1. Что потребуется

- Docker Desktop для macOS/Windows или Docker Engine с Compose plugin для Linux;
- свободные порты `3000`, `8080` и `55432`.

Проверка установки:

```bash
docker --version
docker compose version
```

## 2. Первый запуск

Откройте терминал в корне репозитория и выполните:

```bash
docker compose up --build
```

При первом запуске Docker скачает базовые образы и соберёт frontend/backend. Затем:

1. PostgreSQL дождётся готовности принимать подключения.
2. Backend подключится к БД и автоматически выполнит Flyway-миграции из `src/main/resources/db/migration`.
3. При пустой БД backend создаст демонстрационную учебную сессию и карточку.
4. Frontend запустится после успешной health-проверки backend.

Готовность можно проверить в другом терминале:

```bash
docker compose ps
curl http://localhost:8080/actuator/health
```

У всех трёх контейнеров должен быть статус `Up`; backend и PostgreSQL со временем получают статус `healthy`.

## 3. Адреса и учётная запись

| Компонент | Адрес |
|---|---|
| Интерфейс обучающегося | `http://localhost:3000` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Backend API | `http://localhost:8080/api/v1` |
| Health check | `http://localhost:8080/actuator/health` |
| PostgreSQL с хоста | `localhost:55432` |

Данные для входа:

- логин: `trainee`;
- пароль: `trainee`.

В Swagger дополнительно укажите заголовок версии контракта `0.2`. Полное прохождение API через Swagger описано в [`frontend/SWAGGER_GUIDE.md`](frontend/SWAGGER_GUIDE.md).

## 4. Остановка, повторный запуск и сохранность данных

Остановить приложения, сохранив БД:

```bash
docker compose down
```

Повторно запустить:

```bash
docker compose up --build
```

Состояние занятия, карточек, звонков, оценки и журнал realtime-событий находится в именованном Docker volume. Оно сохраняется после `docker compose down` и перезапуска backend.

Полностью удалить учебные данные и начать сценарий заново:

```bash
docker compose down -v
docker compose up --build
```

> `docker compose down -v` безвозвратно удаляет PostgreSQL volume этого проекта. Не используйте команду, если данные нужно сохранить.

## 5. Запуск в фоне и просмотр логов

Запуск без занятого терминала:

```bash
docker compose up --build -d
```

Логи всех компонентов:

```bash
docker compose logs -f
```

Логи только backend или БД:

```bash
docker compose logs -f backend
docker compose logs -f postgres
```

Выйти из просмотра логов можно сочетанием `Ctrl+C`; контейнеры продолжат работу.

## 6. Изменение портов и паролей

Скопируйте пример настроек:

```bash
cp .env.example .env
```

Основные параметры:

```dotenv
POSTGRES_DB=arm112
POSTGRES_USER=arm112
POSTGRES_PASSWORD=arm112
POSTGRES_PORT=55432
BACKEND_PORT=8080
FRONTEND_PORT=3000
```

Если меняете `BACKEND_PORT`, обновите также `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_WS_URL` и `ARM112_ALLOWED_ORIGINS` перед сборкой. Значения `NEXT_PUBLIC_*` встраиваются во frontend командой `docker compose build`, поэтому после их изменения нужен повторный `docker compose up --build`.

Файл `.env` не следует коммитить с рабочими паролями и секретами.

## 7. Локальный backend с PostgreSQL из Compose

Этот режим удобен при разработке Java-кода: PostgreSQL работает в контейнере, а backend запускается Maven на хосте.

Поднимите только БД:

```bash
docker compose up -d postgres
```

Запустите backend с адресом опубликованного порта PostgreSQL:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/arm112 \
SPRING_DATASOURCE_USERNAME=arm112 \
SPRING_DATASOURCE_PASSWORD=arm112 \
mvn spring-boot:run
```

Затем отдельно запустите frontend:

```bash
cd frontend
npm install
npm run dev
```

Для остановки БД без удаления данных вернитесь в корень репозитория и выполните `docker compose down`.

## 8. Просмотр данных напрямую

Открыть `psql` внутри контейнера:

```bash
docker compose exec postgres psql -U arm112 -d arm112
```

Полезные read-only запросы:

```sql
select state_key, revision, created_at, updated_at from training_state;
select session_id, sequence_number, event_type, occurred_at
from realtime_event
order by session_id, sequence_number;
select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;
```

Выход из `psql`: `\q`.

## 9. Проверка сохранения после перезапуска

1. Откройте интерфейс и примите карточку.
2. Перезапустите только backend:

   ```bash
   docker compose restart backend
   ```

3. Дождитесь `healthy` в `docker compose ps`.
4. Обновите страницу и снова войдите при необходимости.

Статус карточки и timeline должны остаться прежними. REST replay также возвращает записанные до перезапуска realtime-события.

## 10. Частые проблемы

### Порт уже занят

Измените нужный порт в `.env`, например:

```dotenv
BACKEND_PORT=18080
FRONTEND_PORT=13000
POSTGRES_PORT=55433
ARM112_ALLOWED_ORIGINS=http://localhost:13000
NEXT_PUBLIC_API_BASE_URL=http://localhost:18080/api/v1
NEXT_PUBLIC_WS_URL=ws://localhost:18080/ws/v1
```

Затем пересоберите стенд: `docker compose up --build`.

### Backend не стартует

Проверьте состояние БД и последние сообщения:

```bash
docker compose ps
docker compose logs --tail=200 postgres backend
```

Backend ожидает healthy-состояния PostgreSQL. Ошибки Flyway означают, что миграция схемы не была применена; не исправляйте уже выполненный файл миграции задним числом — добавьте следующую версию `V2__...sql`.

### Frontend показывает ошибку сети

Проверьте health backend, адреса `NEXT_PUBLIC_*` и разрешённый origin. После изменения frontend-переменных обязательно выполните сборку заново.

## 11. Текущая модель хранения

Для первой версии состояние одного учебного занятия сохраняется атомарным JSON-снимком в таблице `training_state`. Realtime-события хранятся отдельными строками в `realtime_event`, чтобы replay по `sequence` работал после перезапуска.

Это сохраняет существующий API и позволяет frontend/backend разрабатываться независимо. Ограничения текущей реализации:

- рассчитана на один демонстрационный учебный контур и один экземпляр backend;
- cache ключей идемпотентности находится в памяти процесса, хотя результат команды в состоянии занятия сохраняется;
- запись snapshot и публикация realtime-события пока не объединены одной транзакцией/outbox;
- для нескольких занятий и горизонтального масштабирования модель нужно нормализовать и добавить координацию последовательностей.
