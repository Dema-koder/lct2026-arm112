-- Баллы по каждой карточке до усреднения по сессии.
--
-- Два потребителя:
--   1) блок «по карточкам» на экране разбора для обучающегося (DASHBOARDS.md §4.2);
--   2) матрица «обучающийся × сценарий → результат» для оценки сложности сценариев
--      по Рашу (METRICS.md §5.6, задача B4 плана) — сессионного балла для неё мало,
--      потому что он усредняет разные сценарии в одно число.
--
-- Критерии, неприменимые к режиму, остаются null: у карточки оператора 112 нет
-- действий и коммуникации, у карточки ДДС — адреса, типа и служб.
create table assessment_card (
    id             uuid primary key,
    assessment_id  uuid not null references assessment (id),
    session_id     uuid not null references training_session (id),
    lesson_id      uuid not null references lesson (id),
    trainee_id     uuid not null references app_user (id),
    mode           varchar(20) not null,
    card_id        uuid not null,
    scenario_id    varchar(64),
    address        numeric(5,2),
    classification numeric(5,2),
    services       numeric(5,2),
    timing         numeric(5,2),
    language       numeric(5,2),
    actions        numeric(5,2),
    communication  numeric(5,2),
    spent_seconds  bigint,
    issues         integer not null default 0,
    critical_issues integer not null default 0,
    created_at     timestamp with time zone not null default current_timestamp
);

-- матрица для Раша и разрез «ошибки по сценариям»
create index idx_card_scenario on assessment_card (scenario_id, trainee_id);
create index idx_card_trainee on assessment_card (trainee_id, created_at);
create index idx_card_assessment on assessment_card (assessment_id);
create index idx_card_lesson on assessment_card (lesson_id);
