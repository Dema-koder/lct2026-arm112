# Реализация ролей: изменения в коде по этапам

> **Статус (2026-09-19): реализовано целиком**, см. [history/2026-09-19-4-roles-and-card-fill.md](../history/2026-09-19-4-roles-and-card-fill.md). Отличия от плана: миграции V2–V5 применены одним заходом; сиды собирает `tools/build_seed.py` (вместо двух скриптов); `ScenarioGenerator` реализован шаблонно внутри `ScenarioService`; `CardFillService` и `LessonService` вынесены как отдельные сервисы над `TrainingEngine.SessionState`; настройки в колонке `setting_value` (слово `value` зарезервировано в H2).

Технический план к [ROLES.md](ROLES.md). Там — решения и почему; здесь — какие файлы создать, какие изменить, с какими сигнатурами, миграциями и эндпоинтами. Этапы те же (1–6), порядок тот же: заполнение карточки первым, фундамент ролей параллельно.

Общие правила для всех этапов:

- Контракт [openapi.yaml](contracts/openapi.yaml) стоит с `additionalProperties: false` — каждое новое поле ответа правится в контракте в том же коммите. Версия контракта поднимается один раз, на этапе 2, до `0.3.0`; дальше — только добавления внутри `0.3.x`.
- Миграции Flyway только добавляются (`V2__…`, `V3__…`); `V1` не трогаем.
- Тесты бэкенда идут на H2 в режиме PostgreSQL ([src/test/resources/application.yaml](../src/test/resources/application.yaml)) — в SQL не использовать `jsonb`, `gen_random_uuid()`, `on conflict`; UUID генерировать в Java.
- Каждый этап заканчивается записью в [history/](../history/).

Обозначения: 🆕 новый файл · ✏️ изменение · 🗑 удаление.

---

## Этап 1. Режим заполнения карточки (`CARD_FILL`)

Цель: обучающийся получает вводную «со слов заявителя» и заполняет карточку оператора 112; по «сохранить» — детерминированная оценка адреса, типа, служб, времени. Работает на текущем единственном пользователе, ролей не требует.

### 1.1. Данные сценариев

🆕 `tools/parse-tickets.py` — разбирает [docs/materials/tickets.md](materials/tickets.md) (таблицы `| № | Ситуация | Адрес |`) в `src/main/resources/seed/scenarios.json`. Курсив в колонке «Адрес» → `expectedAddress`, остальное → `rawAddress`. ФИО и телефон вырезаются из «Ситуации» регуляркой в `caller`. Категория (`FIRE` / `MEDICAL` / `POLICE` / `RESCUE` / `UTILITY` / `OTHER`) — по ключевым словам, с ручной правкой в JSON. Запуск с `newline=""`, чтобы не получить CRLF (грабли из history).

Формат одной записи `scenarios.json`:

```json
{
  "id": "ticket-01-1",
  "source": "TICKET",
  "ticket": 1, "ordinal": 1,
  "category": "FIRE",
  "difficulty": 3,
  "callerText": "Возгорание мусорного контейнера, пострадавших нет",
  "caller": {"fullName": "Сидоров Иван Сергеевич", "phone": "9161263471"},
  "rawAddress": "Москва, Депо, около ст. Москва-Пассажирская Киевская, длинное помещение недалеко от участкового пункта полиции",
  "expectedAddress": {"locality": "Москва", "street": "МЖД Киевская", "house": "1 км", "structure": "2"},
  "expectedIncidentTypes": ["fire.garbage"],
  "expectedServices": ["101"]
}
```

🆕 `src/main/resources/seed/incident-types.json` — типы происшествий для плашек и поиска: `id`, `label`, `synonyms[]`, `frequent: bool`, `significant: bool`, `surveyCardId`. Первая версия — 15–20 типов, покрывающих билеты; полный список из ~50 — по мере появления данных.

🆕 `src/main/resources/seed/survey-cards.json` — опросные карты: `id`, `incidentTypeId`, `questions[] {id, text, kind: CHOICE|TEXT, options[] {id, label}, classifierTag}`. Формат по [card-ui.md](materials/card-ui.md) («Опросная карта»): плоский список, ответы кнопками, последний — текст. Стартовый набор: пожар (дом/квартира/мусор), ДТП, взрыв, медицина, человек в опасности.

🆕 `src/main/resources/seed/service-matrix.json` — выжимка из [classifier.json](materials/data/classifier.json): `incidentTypeId → services[]`. Генерируется скриптом `tools/build-service-matrix.py`.

### 1.2. Миграция

🆕 `src/main/resources/db/migration/V2__scenarios.sql`

```sql
create table scenario (
    id                    varchar(64) primary key,
    source                varchar(20) not null,          -- TICKET | GENERATED | TRAINEE_MADE
    category              varchar(20) not null,
    difficulty            smallint not null default 5,
    payload               text not null,                 -- JSON записи выше
    reference_confirmed_by uuid,
    reference_confirmed_at timestamp with time zone,
    created_by            uuid,
    created_at            timestamp with time zone not null default current_timestamp
);
create index idx_scenario_category on scenario (category, difficulty);
```

`payload` — JSON целиком; нормализовать поля эталона в колонки нет смысла, пока структура эталона не утверждена (ROLES.md, раздел 6).

### 1.3. Бэкенд

🆕 `persistence/ScenarioRepository.java` — `JdbcTemplate`: `findAll()`, `findById(String)`, `findByCategory(String, int limit)`, `insert(ScenarioRecord)`, `confirmReference(String id, UUID by)`. Запись — `record ScenarioRecord(String id, String source, String category, int difficulty, Scenario payload, …)`.

🆕 `service/ScenarioSeeder.java` — `@PostConstruct`: если `scenario` пуста, загрузить `seed/scenarios.json`. Идемпотентно.

🆕 `service/ReferenceDataService.java` — читает три JSON из `seed/` в память один раз: `incidentTypes()`, `searchIncidentTypes(String query)` (поиск по неизменяемой части слова и синонимам — как в оригинале), `surveyCard(String incidentTypeId)`, `servicesFor(Set<String> incidentTypeIds)`.

✏️ `api/ApiModels.java` — добавить:

```java
public record Scenario(String id, String source, String category, int difficulty,
                       String callerText, Caller caller, String rawAddress,
                       FormalAddress expectedAddress, List<String> expectedIncidentTypes,
                       List<String> expectedServices) {}

public record FormalAddress(String country, String region, String locality, String object,
                            String okrug, String district, String street, String house,
                            String building, String structure, String apartment,
                            String entrance, String floor, String code, String descriptive) {}

public record CardDraft(UUID id, UUID sessionId, String scenarioId, String number,
                        Instant startedAt, Instant savedAt, String state,       // DRAFT | SAVED
                        String callerText,                                       // вводная
                        DraftPhones phones, DraftCaller caller, FormalAddress address,
                        DraftFlags flags, List<String> incidentTypeIds,
                        List<SurveyAnswer> surveyAnswers, String description,
                        List<DraftService> services, Instant deadlineAt) {}

public record DraftPhones(String ani, String provided, String onSite) {}
public record DraftCaller(String fullName, String status) {}
public record DraftFlags(boolean victims, Integer victimsCount, boolean ambulanceRefused,
                         boolean blocked, boolean noContact, boolean callDropped) {}
public record SurveyAnswer(String questionId, String optionId, String text) {}
public record DraftService(String code, String label, boolean auto) {}

public record CardDraftPatch(DraftPhones phones, DraftCaller caller, FormalAddress address,
                             DraftFlags flags, List<String> incidentTypeIds,
                             List<SurveyAnswer> surveyAnswers,
                             @Size(max = 1999) String description,
                             List<String> extraServiceCodes) {}

public record IncidentTypeItem(String id, String label, boolean frequent, boolean significant) {}
public record SurveyCard(String id, String incidentTypeId, List<SurveyQuestion> questions) {}
public record SurveyQuestion(String id, String text, String kind, List<DictionaryItem> options) {}
```

✏️ `api/ApiModels.TrainingSession` — поле `mode` теперь `CARD_FILL | CARD_ACTIONS`; добавить `List<UUID> draftIds`.

✏️ `persistence/TrainingStateStore.TrainingSnapshot` — добавить `List<CardDraft> drafts`.

🆕 `service/CardFillService.java` — логика режима заполнения (отдельно от `TrainingEngine`, чтобы не раздувать 658 строк):

```java
CardDraft start(UUID sessionId, String scenarioId, UUID actorId);      // создаёт черновик, таймер 3 мин
CardDraft patch(UUID draftId, CardDraftPatch patch, UUID actorId);      // автосохранение полей
CardDraft save(UUID draftId, String idempotencyKey, UUID actorId);     // фиксирует savedAt, state=SAVED,
                                                                        // создаёт scenario(source=TRAINEE_MADE)
List<DraftService> recomputeServices(CardDraft d);                       // автоподбор по типам; ручные — не трогать
```

При `patch` с изменением `incidentTypeIds` службы пересчитываются: `auto=true` заменяются, `auto=false` остаются. Удаление автослужбы запрещено (как в оригинале) — `422 VALIDATION_ERROR`.

🆕 `service/assessment/CardFillAssessor.java` — оценка сохранённого черновика против `Scenario`:

| Критерий | Метод | Вес по умолчанию |
|---|---|---|
| Адрес | `AddressMatcher.score(FormalAddress actual, FormalAddress expected)`: нормализация (нижний регистр, `ё→е`, убрать пунктуацию, «ул./улица», «д./дом»), улица — Левенштейн ≤ 2, дом — точное совпадение. Отдельный `issue ADDRESS_STREET_MISMATCH severity CRITICAL` (кейс «Дубнинская/Дубининская») | 40 |
| Тип происшествия | Jaccard `actual.incidentTypeIds` ↔ `expectedIncidentTypes` | 20 |
| Службы | Jaccard по кодам; пропущенная обязательная служба — `SERVICE_MISSING CRITICAL` | 20 |
| Время | `savedAt − startedAt` ≤ 3 мин → 100, далее линейно до 0 на 6 мин | 15 |
| Грамотность | `LanguageChecker.score(description)` — этап 1: заглушка 100 с `TODO`; см. этап 4.6 | 5 |

Веса — в `application.yaml` под `arm112.assessment.card-fill.*`, чтобы преподаватель потом мог их менять из UI (этап 5).

Результат — тот же `Assessment` (расширить `Assessment` полем `String mode` и `Double addressScore`, `Double classificationScore`, `Double servicesScore`).

✏️ `service/TrainingEngine.java`:
- `session()` возвращает `mode` из состояния сессии, не константу;
- `submit()` — для `CARD_FILL` требует `drafts.allMatch(state == SAVED)` и зовёт `CardFillAssessor` вместо текущего расчёта;
- `seed()` — сидовая сессия становится `CARD_FILL` с одним сценарием `ticket-01-1` (сидовую карточку `CARD_ACTIONS` оставить в `restore`-совместимости, но по умолчанию демо стартует с заполнения — решение №6).

✏️ `api/TraineeController.java` — добавить:

```
GET  /references/incident-types?query=          → List<IncidentTypeItem>
GET  /references/survey-cards/{incidentTypeId}  → SurveyCard
GET  /card-drafts?sessionId=                    → List<CardDraft>
POST /card-drafts            {sessionId, scenarioId}  → 201 CardDraft
GET  /card-drafts/{id}                          → CardDraft
PATCH /card-drafts/{id}      CardDraftPatch     → CardDraft
POST /card-drafts/{id}/save  Idempotency-Key    → CardDraft
```

✏️ `security/SecurityConfig.java` — `setAllowedMethods` добавить `PATCH`.

✏️ `service/EventService` — новые типы событий: `draft.created`, `draft.updated`, `draft.saved`.

✏️ `docs/contracts/openapi.yaml` — тег `Drafts`, пути выше, схемы выше; `TrainingSession.mode` → `enum [CARD_FILL, CARD_ACTIONS]`; `Assessment` — новые поля.

### 1.4. Фронтенд

Разбить `app/page.tsx` (691 строка) до того, как добавлять второй экран:

```
frontend/app/
  page.tsx                      ← только Home: восстановление токена, выбор экрана по mode/role
  components/
    Login.tsx                   ← вынести как есть
    Modal.tsx
    dds/                        ← текущий режим CARD_ACTIONS
      Workspace.tsx  IncidentJournal.tsx  CardWorkspace.tsx
    fill/                       ← 🆕 режим CARD_FILL
      FillWorkspace.tsx         ← загрузка сессии, черновика, автосохранение PATCH с debounce 500 мс
      CallerPanel.tsx           ← три телефона + заявитель; вводная «со слов заявителя» — здесь же,
                                   всплывающим блоком «трубка» поверх блока телефонов (решение из ROLES.md, этап 1)
      AddressForm.tsx           ← 15 полей формализованного адреса + «описательный адрес»
      FlagsBar.tsx              ← Пострадавшие / Отказ от скорой / Заблокированные / нет контакта / срыв звонка
      WhatHappened.tsx          ← частые плашки, значимые типы, поиск по строке, выбранные синим
      SurveyCard.tsx            ← вопросы/варианты по выбранному типу, строка «Класс.: …»
      DescriptionField.tsx      ← textarea, счётчик 0 / 1999
      ServicesDock.tsx          ← оранжевая панель, автоподобранные синим, «+» → окно добавления
      FillTimer.tsx             ← 00:09 «минут / секунд», номер карточки, «Опер., АРМ N»
  lib/
    api.ts                      ← типы + функции для новых эндпоинтов
    format.ts                   ← 🆕 dateTime/timeOnly/countdown/elapsed из page.tsx
```

✏️ `app/globals.css` — блок `.fill-*`: оранжевая панель `--orange` (уже объявлен), синие плашки выбранных типов/признаков `#0784c6`, красные флаги «нет контакта» / «срыв звонка». Сетка — по кадрам `card-01.png`, `card-06.png`: левая колонка (телефоны, заявитель, адрес), правая (флаги, «что случилось», опросная карта), низ — описание и службы. Единственная кнопка действия — «сохранить» (решение №11).

✏️ `lib/api.ts` — типы `Scenario`, `CardDraft`, `CardDraftPatch`, `IncidentTypeItem`, `SurveyCard`; функции `drafts(token, sessionId)`, `createDraft`, `patchDraft`, `saveDraft`, `incidentTypes(query)`, `surveyCard(id)`.

✏️ `tests/rendered-html.test.mjs` — проверки на «Описательный адрес», «Что случилось», «сохранить», `0 / 1999`.

### 1.5. Тесты

🆕 `src/test/java/ru/lct/arm112/CardFillFlowIntegrationTest.java`: создать черновик → PATCH адреса с опечаткой в улице → выбрать тип → службы подобрались → save → submit → `assessment.addressScore < 100`, issue `ADDRESS_STREET_MISMATCH`.

🆕 `src/test/java/ru/lct/arm112/assessment/AddressMatcherTest.java`: «Дубнинская» vs «Дубининская» → mismatch; «ул. Берзарина, д. 21» vs `{street: Берзарина, house: 21}` → match.

---

## Этап 2. Учётные записи, RBAC, аудит

### 2.1. Миграция

🆕 `V3__users_audit.sql`

```sql
create table app_user (
    id                 uuid primary key,
    login              varchar(100) not null unique,
    password_hash      varchar(100) not null,
    display_name       varchar(200) not null,
    role               varchar(20) not null,          -- ADMIN | TEACHER | TRAINEE
    workstation_number varchar(10),                    -- только для TRAINEE (решение №2)
    group_id           uuid,                           -- FK добавится в V4
    active             boolean not null default true,
    created_by         uuid,
    created_at         timestamp with time zone not null default current_timestamp,
    updated_at         timestamp with time zone not null default current_timestamp
);

create table audit_event (
    id            uuid primary key,
    actor_user_id uuid,
    actor_role    varchar(20),
    action        varchar(100) not null,               -- 'POST /api/v1/cards/{id}/acceptance', 'auth.login', …
    resource_type varchar(50),
    resource_id   varchar(100),
    http_status   smallint,
    request_id    uuid,
    client_ip     varchar(64),
    payload       text,                                 -- тело запроса, обрезанное до 4 КБ, пароли вырезаны
    occurred_at   timestamp with time zone not null default current_timestamp
);
create index idx_audit_actor_time on audit_event (actor_user_id, occurred_at);
create index idx_audit_time on audit_event (occurred_at);

create table app_setting (
    key        varchar(100) primary key,
    value      text not null,
    updated_by uuid,
    updated_at timestamp with time zone not null default current_timestamp
);
insert into app_setting (key, value) values ('audit.retention_days', '180');
```

Сид пользователей — не в SQL (bcrypt-хеш надо считать), а в `UserSeeder` (ниже).

### 2.2. Бэкенд

🆕 `persistence/UserRepository.java` — `findByLogin`, `findById`, `findAll(role?)`, `insert`, `update`, `setActive`, `setPasswordHash`. Модель `record AppUser(UUID id, String login, String passwordHash, String displayName, Role role, String workstationNumber, UUID groupId, boolean active)`.

🆕 `security/Role.java` — `enum Role { ADMIN, TEACHER, TRAINEE }`.

🆕 `service/UserSeeder.java` — `@PostConstruct`: если `app_user` пуста, создать `admin/admin`, `teacher/teacher`, `trainee/trainee` (АРМ `12`). Пароли — из `arm112.seed.*` в `application.yaml`, чтобы на стенде их можно было переопределить env-переменными.

🆕 `security/CurrentUser.java` — `record CurrentUser(UUID id, String login, Role role)` + `static CurrentUser from(Jwt jwt)`; резолвер аргументов `@AuthenticationPrincipal` → `CurrentUser` через `HandlerMethodArgumentResolver` в `config/WebConfig.java` 🆕.

✏️ `security/JwtService.issue(AppUser user)` — клеймы `role` = `user.role().name()`, `preferred_username` = login, `name` = displayName.

✏️ `security/SecurityConfig.java`:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(PUBLIC…).permitAll()
    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
    .requestMatchers("/api/v1/teacher/**").hasRole("TEACHER")
    .requestMatchers("/api/v1/auth/**").authenticated()
    .requestMatchers("/api/v1/**").hasAnyRole("TRAINEE", "TEACHER")   // преподаватель — «делай, как я»
    .anyRequest().authenticated())
.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roleConverter())))
```

`roleConverter()` — `JwtAuthenticationConverter` с `JwtGrantedAuthoritiesConverter`, читающим клейм `role` в `ROLE_<role>`. Добавить `@EnableMethodSecurity` для `@PreAuthorize` в сервисах. Swagger-пути (`/swagger-ui/**`, `/v3/api-docs/**`, `/openapi.yaml`) — `hasRole("ADMIN")` вместо `permitAll` (матрица прав) — но тогда Swagger UI не сможет получить спеку до логина; оставить `permitAll` на спеку, `ADMIN` — на UI. Решить при реализации, отметить в history.

✏️ `api/AuthController.java`:
- `login` — `users.findByLogin`, `active`, `passwordEncoder.matches`; неуспех — `audit.record("auth.login_failed", …)`; успех — `audit.record("auth.login", …)`;
- `me` — по `CurrentUser`;
- 🆕 `POST /auth/password {current, next}` — смена своего пароля (все роли).

🆕 `service/AuditService.java` — `record(CurrentUser actor, String action, String resourceType, String resourceId, int status, UUID requestId, String ip, String payload)`; 🆕 `persistence/AuditRepository.java` — `insert`, `query(filter, limit, cursor)`, `deleteOlderThan(Instant)`.

🆕 `config/AuditFilter.java` — `OncePerRequestFilter` после Security: оборачивает запрос в `ContentCachingRequestWrapper`, для всех **не-GET** запросов под `/api/v1/**` после `chain.doFilter` пишет `audit_event` (actor из `SecurityContext`, action = `METHOD + pattern` из `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, payload = тело с вырезанными полями `password`, `current`, `next`). Один фильтр закрывает требование «все действия всех ролей» без аннотаций на каждом методе.

🆕 `service/AuditRetentionJob.java` — `@Scheduled(cron = "0 0 3 * * *")`: `deleteOlderThan(now − max(180, settings.audit.retention_days))`. Ниже 180 дней опустить нельзя (ТЗ: ≥ 6 месяцев).

✏️ `service/TrainingEngine` / `CardFillService` — все публичные методы получают `CurrentUser actor`; `addTimeline` пишет `actorUserId` и `actorRole`; `actorLabel` — `displayName`, для `TEACHER` — «Преподаватель: <ФИО>» (чтобы «делай, как я» было видно в ленте и не попадало в оценку — `Assessor` пропускает записи с `actorRole == TEACHER`).

✏️ `api/ApiModels.User` — `role` остаётся `String`, но контракт — `enum`. `CardTimelineEntry` — добавить `actorRole`.

✏️ `docs/contracts/openapi.yaml` — `info.version: 0.3.0`; `User.role: enum [ADMIN, TEACHER, TRAINEE]`; `CardTimelineEntry.actorRole`; путь `/auth/password`. ✏️ `config/ContractVersionFilter.java` — принимаемая версия `0.3`.

✏️ `src/test/resources/application.yaml` — `arm112.seed.*` с тестовыми паролями.

### 2.3. Фронтенд

✏️ `lib/api.ts` — `User.role: "ADMIN" | "TEACHER" | "TRAINEE"`; `changePassword`.
✏️ `app/page.tsx` — после `me()` хранить `user`; `switch (user.role)`: `TRAINEE` → по `mode` `FillWorkspace` | `dds/Workspace`; `TEACHER` → `teacher/TeacherShell` (этап 4); `ADMIN` → `admin/AdminShell` (этап 5). До этапов 4–5 — заглушка «Экран в разработке» с выходом.
✏️ `components/Login.tsx` — без изменений (общий экран входа).

### 2.4. Тесты

🆕 `SecurityRbacTest` (`@SpringBootTest` + `MockMvc`): `trainee` на `/admin/users` → 403; `teacher` на `/cards` → 200; без токена → 401; после `POST /cards/{id}/acceptance` в `audit_event` одна запись с `actor_role = TRAINEE` и без поля `password` в payload.

---

## Этап 3. Занятия, персональные сессии, группы, рейтинг

### 3.1. Миграция

🆕 `V4__lessons.sql`

```sql
create table training_group (
    id         uuid primary key,
    name       varchar(200) not null,
    teacher_id uuid not null references app_user (id),
    created_at timestamp with time zone not null default current_timestamp
);
alter table app_user add constraint fk_user_group foreign key (group_id) references training_group (id);

create table lesson (
    id                   uuid primary key,
    teacher_id           uuid not null references app_user (id),
    group_id             uuid references training_group (id),
    title                varchar(200) not null,
    kind                 varchar(20) not null,   -- TRAINING | CHECK | EXAM   (решение №10)
    mode                 varchar(20) not null,   -- CARD_FILL | CARD_ACTIONS
    card_source          varchar(20) not null,   -- GENERATED | TRAINEE_MADE | MIXED
    state                varchar(20) not null,   -- DRAFT | ACTIVE | COMPLETED
    scenario_ids         text not null,          -- JSON-массив
    created_at           timestamp with time zone not null default current_timestamp,
    started_at           timestamp with time zone,
    completed_at         timestamp with time zone,
    results_published_at timestamp with time zone
);

create table training_session (                  -- персональное занятие (решение №1)
    id                 uuid primary key,
    lesson_id          uuid not null references lesson (id),
    trainee_id         uuid not null references app_user (id),
    workstation_number varchar(10),               -- копия из app_user на момент старта (решение №2)
    state              varchar(20) not null,      -- PENDING | ACTIVE | COMPLETED
    started_at         timestamp with time zone,
    completed_at       timestamp with time zone,
    unique (lesson_id, trainee_id)
);
create index idx_session_trainee_state on training_session (trainee_id, state);

create table assessment (
    id                    uuid primary key,
    session_id            uuid not null references training_session (id),
    mode                  varchar(20) not null,
    ai_payload            text not null,          -- JSON ApiModels.Assessment как считает система
    ai_total              numeric(5,2) not null,
    teacher_id            uuid references app_user (id),
    teacher_total         numeric(5,2),           -- решение №5: рядом, nullable
    teacher_comment       text,
    teacher_assessed_at   timestamp with time zone,
    created_at            timestamp with time zone not null default current_timestamp
);
create index idx_assessment_session on assessment (session_id);
```

`training_state.state_key` — теперь `session_id.toString()`: по одной строке снимка на персональную сессию. Это минимальная правка `TrainingStateStore`, а карточки/звонки/timeline остаются в JSON, как сейчас. Оценки уходят в таблицу, потому что по ним строятся отчёты и рейтинг поперёк сессий.

### 3.2. Бэкенд

🆕 `persistence/GroupRepository`, `LessonRepository`, `SessionRepository`, `AssessmentRepository` — по образцу `ScenarioRepository`.

✏️ `persistence/TrainingStateStore.java` — `load(UUID sessionId)`, `save(UUID sessionId, TrainingSnapshot)`, `delete(UUID sessionId)`; убрать константу `STATE_KEY`.

✏️ `service/TrainingEngine.java` — главный рефакторинг этапа. Сейчас: поля `cards`, `calls`, `assessments`, `sessionState`, `sessionStartedAt` — глобальные. Станет:

```java
private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();   // sessionId → состояние

private static final class SessionState {
    final UUID id; final UUID lessonId; final UUID traineeId; final String mode;
    volatile String state; volatile Instant startedAt, completedAt;
    final Map<UUID, MutableCard> cards; final Map<UUID, MutableCall> calls;
    final Map<UUID, CardDraft> drafts;
}
```

- 🗑 константы `USER_ID`, `WORKSTATION_ID`, `SESSION_ID`, `CARD_ID`, метод `seed()` — сидовая сессия исчезает; демо создаётся через `LessonService` (сид `DemoLessonSeeder` 🆕: группа «Демо», занятие `CHECK`/`CARD_FILL` с `ticket-01-1`, если в базе нет ни одного занятия).
- `initialize()` — `sessionRepo.findByState(ACTIVE)` → для каждой `stateStore.load(id)` → `restore`.
- Каждый публичный метод получает `CurrentUser actor` и начинается с `SessionState s = requireAccessible(sessionId, actor)`: `TRAINEE` — только `s.traineeId == actor.id`; `TEACHER` — только `lesson.teacherId == actor.id` (решение №8); иначе `403 FORBIDDEN`. Для методов по `cardId`/`callId`/`draftId` — сначала найти сессию по индексу `Map<UUID, UUID> cardToSession`.
- `detectOverdue()` — цикл по всем `sessions`.
- `persist(sessionId)` — снимок одной сессии.
- `submit()` — пишет `assessment` в таблицу (`ai_*`), `training_session.state = COMPLETED`; если все сессии занятия завершены — `lesson.state = COMPLETED` (преподаватель может завершить и принудительно, этап 4).
- `assessment(id, actor)` — правило видимости (решение №10):

```java
Lesson lesson = …;
if (actor.role() == TRAINEE) {
    switch (lesson.kind()) {
        case TRAINING, CHECK -> { /* доступно сразу после submit */ }
        case EXAM -> { if (lesson.resultsPublishedAt() == null) throw new ApiException(FORBIDDEN, "RESULTS_NOT_PUBLISHED", "Результаты появятся после проверки преподавателем"); }
    }
}
return AssessmentService.finalOf(row);   // teacher_total != null ? teacher : ai
```

🆕 `service/LessonService.java` — `create`, `start` (создаёт `training_session` для каждого выбранного обучающегося с `workstation_number` из `app_user`, создаёт карточки/черновики по `scenario_ids` и `card_source`, публикует `training.session_started` каждому), `complete` (принудительно закрывает незавершённые сессии с `assessment` по факту), `publish` (только `EXAM`), `monitor`.

Источник карточек для `CARD_ACTIONS` (решение №6): `GENERATED` → `scenario.source in (TICKET, GENERATED)`; `TRAINEE_MADE` → `scenario.source = TRAINEE_MADE` (созданы `CardFillService.save`); `MIXED` — объединение. Черновик `CARD_FILL` → `scenario.payload` конвертирует `CardDraft` в `Scenario`: адрес обучающегося становится `rawAddress`, эталон — из исходного сценария.

🆕 `service/RatingService.java` — `traineeRating(UUID traineeId)`: `Σ(final_total × difficulty) / Σ(difficulty)` по завершённым сессиям; `groupRating(UUID groupId)`: среднее по участникам + распределение. Один SQL с join `assessment → training_session → lesson`, `final_total = coalesce(teacher_total, ai_total)`, сложность — средняя по `scenario_ids` занятия.

✏️ `service/EventService.java` — адресная доставка вместо broadcast всем:

```java
public void register(WebSocketSession socket)            // читает attributes "subject" → userId
public void publish(UUID sessionId, …)                    // как сейчас, но broadcast → deliver(event)
private void deliver(RealtimeEvent e)                     // получатели: trainee сессии + teacher занятия
```

Получатели резолвятся через `SessionAccess.recipientsOf(sessionId)` 🆕 (кэш `sessionId → {traineeId, teacherId}`, инвалидируется при старте занятия). Для преподавателя дополнительно событие `lesson.progress` с агрегатом по занятию.

✏️ `config/WebSocketConfig.java` — в `attributes` класть ещё `role` из тикета; ✏️ `WsTicketService.issue(subject, role)`.

✏️ `api/TraineeController.java`:
- `GET /trainee/context` — `activeSession` = `sessionRepo.findActiveByTrainee(actor.id)`; `workstation.number` — из `app_user`;
- `GET /training-sessions/active` — то же; 🆕 `GET /training-sessions?state=` — список своих;
- `GET /cards?sessionId=` — проверка доступа внутри `engine`.

✏️ `api/ApiModels` — `TrainingSession` + `lessonId`, `lessonKind`, `lessonTitle`; `Assessment` + `source: AI | TEACHER`, `teacherComment`; 🆕 `Rating(double value, int rank, int groupSize)`.

✏️ `docs/contracts/openapi.yaml` — соответственно; `403 RESULTS_NOT_PUBLISHED` у `/assessments/{id}`.

### 3.3. Тесты

✏️ `TrainingFlowIntegrationTest` — переписать на новую модель: `LessonService.create/start` для `trainee`, дальше прежний сценарий; убрать `ReflectionTestUtils` по `"cards"` — вместо этого `engine.forceAcceptanceDeadline(cardId, instant)` под `@Profile("test")`, либо оставить рефлексию по `sessions.get(id).cards`.
🆕 `LessonVisibilityTest` — три занятия `TRAINING`/`CHECK`/`EXAM`: для `EXAM` до `publish` — 403, после — 200 с `source = TEACHER`, если преподаватель оценил.
🆕 `EventRoutingTest` — два обучающихся, событие первого не приходит второму, приходит преподавателю.

---

## Этап 4. Рабочее место преподавателя

### 4.1. Бэкенд — `api/TeacherController.java` 🆕, префикс `/api/v1/teacher`

| Метод | Путь | Тело / ответ |
|---|---|---|
| GET | `/scenarios?category=&source=&confirmed=` | `List<ScenarioListItem>` |
| POST | `/scenarios` | `Scenario` → 201 |
| PUT | `/scenarios/{id}` | `Scenario` (в т.ч. `difficulty`, эталон) |
| POST | `/scenarios/{id}/confirm-reference` | → `Scenario` с `referenceConfirmedAt` |
| POST | `/scenarios/generate` | `{category, count, difficulty}` → `List<Scenario>` (этап 4.5) |
| GET | `/groups` | свои группы с участниками |
| GET | `/lessons?state=` | свои занятия |
| POST | `/lessons` | `{title, groupId, kind, mode, cardSource, scenarioIds[], traineeIds[]}` → 201 |
| POST | `/lessons/{id}/start` | → `Lesson` с созданными сессиями |
| GET | `/lessons/{id}/monitor` | `LessonMonitor { sessions[] { traineeName, workstationNumber, state, currentCardStatus, acceptanceOverdue, processingOverdue, elapsed } }` |
| POST | `/lessons/{id}/complete` | → `Lesson` |
| POST | `/lessons/{id}/publish` | только `kind = EXAM` → `Lesson` с `resultsPublishedAt` |
| GET | `/lessons/{id}/report` | `LessonReport { rows[] { traineeName, workstationNumber, timingScore, syntaxErrors, level, aiTotal, teacherTotal, finalTotal }, groupRating }` |
| GET | `/lessons/{id}/report.csv` | `text/csv; charset=utf-8` с BOM (Excel) |
| GET | `/sessions/{sessionId}` | полная сессия обучающегося: карточки, timeline, черновики, оценка ИИ |
| PUT | `/sessions/{sessionId}/assessment` | `{total, comment}` → `Assessment(source = TEACHER)` (решение №5) |
| POST | `/materials` | multipart `file` + `{title, groupIds[]}` → 201 |
| GET | `/materials` | свои |
| DELETE | `/materials/{id}` | |

Все методы — `@PreAuthorize("hasRole('TEACHER')")` (дублирует `SecurityConfig`, но защищает при переносе пути) и фильтр `teacher_id = actor.id` в репозиториях (решение №8).

🆕 `V5__materials.sql`: `material (id, teacher_id, title, file_name, content_type, size_bytes, storage_path, uploaded_at)`, `material_group (material_id, group_id)`. Файлы — на диск в `arm112.materials.dir` (volume в `compose.yaml`), не в БД.

🆕 `service/ScenarioGenerator.java` — интерфейс `List<Scenario> generate(String category, int count, int difficulty)` с двумя реализациями: `TemplateScenarioGenerator` (комбинирует билеты: адрес одного, ситуацию другого той же категории — работает без модели) и `LlmScenarioGenerator` (локальная модель, `@ConditionalOnProperty arm112.llm.enabled`). Сгенерированное — `source = GENERATED`, `reference_confirmed_at = null`, пока преподаватель не подтвердит.

🆕 `service/assessment/LanguageChecker.java` — заменить заглушку: словарь частотных слов + названия улиц из `expectedAddress` всех сценариев; `syntaxErrors` = число слов вне словаря с Левенштейн-подсказкой; `score = 100 − 10 × errors`, не ниже 0. Этого достаточно для отчёта «количество синтаксических ошибок» (Q&A §16); полноценная проверка — отдельная задача.

✏️ `EventService` — преподавателю: `lesson.progress` при каждом `card.*`/`draft.*` событии в его занятии.

### 4.2. Фронтенд — `components/teacher/`

```
TeacherShell.tsx        ← левое меню в стиле журнала: Занятия · Сценарии · Материалы; шапка как у АРМ
LessonList.tsx          ← таблица своих занятий (колонки как в журнале происшествий: дата, название, вид, режим, состояние)
LessonCreate.tsx        ← одна форма: название, группа, вид, режим, источник, сценарии (чекбоксы), обучающиеся (чекбоксы, АРМ подтягивается)
LessonMonitor.tsx       ← таблица обучающихся: АРМ, ФИО, статус карточки, таймеры, просрочка красным (как в журнале); WS lesson.progress
LessonReport.tsx        ← таблица отчёта + две диаграммы (распределение итоговых баллов, время vs норматив) + «Выгрузить CSV»;
                           для EXAM — кнопка «Опубликовать результаты», появляется только когда все сессии оценены
SessionReview.tsx       ← карточка обучающегося в режиме только-чтение + оценка ИИ + поле оценки/комментария преподавателя
ScenarioList.tsx        ← библиотека: фильтры категория/источник/подтверждён; строка → ScenarioEdit
ScenarioEdit.tsx        ← вводная, эталон по режиму (адрес/типы/службы или цепочка статусов/звонок), сложность 1–10, «Подтвердить эталон»
MaterialList.tsx        ← загрузка, список, назначение группам
```

Диаграммы — без библиотек, inline SVG (две простые гистограммы); это держит сборку без новых зависимостей.

✏️ `lib/api.ts` — `teacher.*` функции по таблице выше.
✏️ `globals.css` — переиспользовать `.incident-*` классы журнала для таблиц преподавателя, чтобы стиль совпал (решение №11); новых цветов не вводить.

### 4.3. Тесты

🆕 `TeacherFlowIntegrationTest`: создать занятие на двух обучающихся → start → у каждого своя `ACTIVE`-сессия с его АРМ → оба submit → report две строки → PUT оценка одному → report `finalTotal` совпадает с оценкой преподавателя у него и с ИИ у другого → второй преподаватель на `/lessons/{id}` → 403.

---

## Этап 5. Рабочее место администратора

### 5.1. Бэкенд — `api/AdminController.java` 🆕, префикс `/api/v1/admin`

| Метод | Путь | Тело / ответ |
|---|---|---|
| GET | `/users?role=&active=` | `List<UserAdminView>` (без хеша) |
| POST | `/users` | `{login, password, displayName, role, workstationNumber, groupId}` → 201 |
| PUT | `/users/{id}` | те же поля без пароля |
| POST | `/users/{id}/block` · `/unblock` | |
| POST | `/users/{id}/reset-password` | `{password}` |
| GET / POST / PUT | `/groups` | `{name, teacherId}` |
| GET | `/settings` | `Map<String,String>` |
| PUT | `/settings` | частичное обновление; валидация `audit.retention_days ≥ 180` |
| GET | `/audit?actorId=&role=&action=&from=&to=&cursor=&limit=` | `AuditPage` |
| GET | `/system/health` | агрегат `actuator/health` + число открытых WS + версия |
| GET | `/backups` | список файлов в `arm112.backup.dir` |
| POST | `/backups` | создать сейчас → `{fileName, sizeBytes, createdAt}` |
| POST | `/backups/{fileName}/restore` | `{confirm: "RESTORE"}` — обязательное подтверждение |

🆕 `service/BackupService.java` — `create()`: экспорт всех прикладных таблиц (`app_user`, `training_group`, `lesson`, `training_session`, `scenario`, `assessment`, `training_state`, `realtime_event`, `app_setting`, `material` + файлы) в один ZIP с JSON на таблицу через `JdbcTemplate`; `restore(file)`: в одной транзакции `delete` в порядке FK, затем `insert`. Не зависит от `pg_dump` в контейнере и одинаково работает на H2 в тестах. `@Scheduled(cron = "${arm112.backup.cron:0 0 2 * * *}")` — ежедневно (ТЗ). Каталог — volume в `compose.yaml` 🆕 `backups:`.

Настройки, читаемые из `app_setting` в рантайме (через `SettingsService` 🆕 с кэшем и инвалидацией по `PUT`): `audit.retention_days`, `telephony.ringing_ms`, `telephony.voice_default`, `assessment.card_fill.weights`, `logging.level.ru.lct.arm112` (применяется через `LoggingSystem`).

`GET /audit` — это тот же `AuditRepository.query`; системный лог ошибок — `logback` `RollingFileAppender` в `logs/` + эндпоинт `GET /admin/system/log?lines=500` (хвост файла). Заодно закрыть долг из history: `ApiExceptionHandler` логировать стектрейсы на 5xx.

### 5.2. Фронтенд — `components/admin/`

```
AdminShell.tsx     ← меню: Пользователи · Группы · Настройки · Журнал · Система
UserList.tsx       ← таблица + строка-форма создания (логин, ФИО, роль, АРМ, группа); блокировка — переключатель в строке, не отдельная кнопка
GroupList.tsx
SettingsForm.tsx   ← поля из GET /settings; сохранение по blur, без кнопки «Сохранить» (решение №11)
AuditLog.tsx       ← таблица с фильтрами, пагинация курсором
SystemPanel.tsx    ← health, «Сделать копию», список копий, «Восстановить» с подтверждающим вводом RESTORE
```

### 5.3. Тесты

🆕 `AdminFlowIntegrationTest`: создать пользователя → войти им → заблокировать → вход 401 → `PUT /settings {audit.retention_days: 30}` → 422 → backup → изменить данные → restore → данные прежние.

---

## Этап 6. Обучающийся: результаты, материалы, рейтинг

### 6.1. Бэкенд — ✏️ `TraineeController`

| Метод | Путь | Ответ |
|---|---|---|
| GET | `/trainee/results` | `List<ResultItem { lessonTitle, kind, completedAt, visible: bool, finalTotal?, source? }>` — `visible=false` для неопубликованного `EXAM` |
| GET | `/trainee/results/{sessionId}` | `Assessment` по правилу видимости (уже реализовано в этапе 3) |
| GET | `/trainee/rating` | `Rating` |
| GET | `/trainee/materials` | назначенные группе |
| GET | `/trainee/materials/{id}/download` | файл |

Подсказки в режиме `TRAINING` (решение №10): `CardFillService.patch` и `TrainingEngine.acceptance/reaction` при `lesson.kind == TRAINING` возвращают в ответе поле `hints: List<Hint {field, message}>` — мгновенная сверка с эталоном (улица не совпадает, не выбрана обязательная служба, пропущен статус). Для `CHECK`/`EXAM` поле пустое. Контракт: `hints` — новое поле у `CardDraft` и `IncidentCard`.

### 6.2. Фронтенд

```
components/trainee/
  ResultsList.tsx      ← таблица занятий; неопубликованный зачёт — «на проверке у преподавателя»
  ResultDetail.tsx     ← оценка, ошибки (issues), рекомендации, «оценка преподавателя» если source = TEACHER
  Materials.tsx
```

Подсказки на тренировке — не отдельный компонент, а подсветка поля/плитки в `fill/*` и `dds/*` по `hints` (тонкая жёлтая рамка + текст под полем).

В шапку журнала и экрана заполнения — ссылка «Мои результаты» (одна, текстовая, как «расширенный по параметрам»).

---

## Сводная таблица файлов

| Этап | Новые | Изменяемые |
|---|---|---|
| 1 | `tools/parse-tickets.py`, `tools/build-service-matrix.py`, `seed/{scenarios,incident-types,survey-cards,service-matrix}.json`, `V2__scenarios.sql`, `ScenarioRepository`, `ScenarioSeeder`, `ReferenceDataService`, `CardFillService`, `assessment/{CardFillAssessor,AddressMatcher,LanguageChecker}`, `components/fill/*` (10 файлов), `lib/format.ts`, 2 теста | `ApiModels`, `TrainingStateStore`, `TrainingEngine`, `TraineeController`, `SecurityConfig` (PATCH), `EventService`, `openapi.yaml`, `page.tsx` (разбиение), `api.ts`, `globals.css`, `rendered-html.test.mjs` |
| 2 | `V3__users_audit.sql`, `UserRepository`, `Role`, `UserSeeder`, `CurrentUser`, `WebConfig`, `AuditService`, `AuditRepository`, `AuditFilter`, `AuditRetentionJob`, `SecurityRbacTest` | `JwtService`, `SecurityConfig`, `AuthController`, `TrainingEngine`, `CardFillService`, `ApiModels`, `openapi.yaml` (0.3.0), `ContractVersionFilter`, `application.yaml` (оба), `api.ts`, `page.tsx` |
| 3 | `V4__lessons.sql`, `Group/Lesson/Session/AssessmentRepository`, `LessonService`, `RatingService`, `SessionAccess`, `DemoLessonSeeder`, 2 теста | `TrainingStateStore`, `TrainingEngine` (рефакторинг на `SessionState`), `EventService`, `WebSocketConfig`, `WsTicketService`, `TraineeController`, `ApiModels`, `openapi.yaml`, `TrainingFlowIntegrationTest` |
| 4 | `TeacherController`, `V5__materials.sql`, `MaterialRepository`, `ScenarioGenerator` + 2 реализации, `components/teacher/*` (9 файлов), `TeacherFlowIntegrationTest` | `LanguageChecker`, `EventService`, `api.ts`, `globals.css`, `openapi.yaml` |
| 5 | `AdminController`, `BackupService`, `SettingsService`, `components/admin/*` (6 файлов), `AdminFlowIntegrationTest`, `logback-spring.xml` | `ApiExceptionHandler` (лог 5xx), `compose.yaml` (volume `backups`), `application.yaml`, `api.ts`, `openapi.yaml` |
| 6 | `components/trainee/*` (3 файла) | `TraineeController`, `CardFillService`, `TrainingEngine` (hints), `components/fill/*`, `components/dds/*`, `api.ts`, `openapi.yaml` |

---

## Что явно не делаем в этих этапах

- Адаптивная сложность и поле «уровень» у обучающегося — [ROLES.md, раздел 6](ROLES.md#адаптивная-сложность-бывшая-неясность-7--не-трогаем). В отчёте колонка «уровень подготовки» = `traineeRating`, без автоматики.
- Нормализация `training_state` в таблицы `card`/`card_timeline`/`outbound_call` — снимок на сессию достаточен, пока отчёты строятся по `assessment`.
- Transactional outbox между снимком и `realtime_event` — долг из history остаётся.
- Идемпотентность между перезапусками — кэш ключей по-прежнему в памяти.
- Звук исходящего вызова и стадия `RINGING` — задача плана fidelity, не ролей; `telephony.ringing_ms` в настройках заводится заранее.
