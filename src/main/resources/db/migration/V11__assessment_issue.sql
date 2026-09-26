-- Замечания оценки отдельными строками: из ai_payload их нельзя агрегировать,
-- а на них держатся «типовые ошибки занятия», «устойчивые недочёты обучающегося»
-- и «ошибки по сценариям» (docs/assessment/METRICS.md §1, DASHBOARDS.md §3.3–3.4).
--
-- lesson_id, trainee_id и scenario_id продублированы сознательно: запросы дашбордов
-- группируют по ним на каждом открытии экрана, а join через training_session
-- на каждый такой запрос не нужен. Строки неизменяемы, рассинхронизации не будет.
create table assessment_issue (
    id            uuid primary key,
    assessment_id uuid not null references assessment (id),
    session_id    uuid not null references training_session (id),
    lesson_id     uuid not null references lesson (id),
    trainee_id    uuid not null references app_user (id),
    mode          varchar(20) not null,
    card_id       uuid,
    scenario_id   varchar(64),
    code          varchar(64) not null,
    severity      varchar(20) not null,
    message       text,
    expected      text,
    actual        text,
    created_at    timestamp with time zone not null default current_timestamp
);

-- типовые ошибки занятия и ошибки в разрезе сценария
create index idx_issue_lesson_code on assessment_issue (lesson_id, code);
-- устойчивые недочёты обучающегося: код + хронология
create index idx_issue_trainee_code on assessment_issue (trainee_id, code, created_at);
-- проблемные сценарии библиотеки
create index idx_issue_scenario_code on assessment_issue (scenario_id, code);
create index idx_issue_assessment on assessment_issue (assessment_id);
