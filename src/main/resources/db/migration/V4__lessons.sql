-- Группы, занятия преподавателя, персональные сессии обучающихся, оценки.
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
    kind                 varchar(20) not null,
    mode                 varchar(20) not null,
    card_source          varchar(20) not null,
    state                varchar(20) not null,
    scenario_ids         text not null,
    created_at           timestamp with time zone not null default current_timestamp,
    started_at           timestamp with time zone,
    completed_at         timestamp with time zone,
    results_published_at timestamp with time zone
);
create index idx_lesson_teacher on lesson (teacher_id, created_at);

create table training_session (
    id                 uuid primary key,
    lesson_id          uuid not null references lesson (id),
    trainee_id         uuid not null references app_user (id),
    workstation_number varchar(10),
    state              varchar(20) not null,
    created_at         timestamp with time zone not null default current_timestamp,
    started_at         timestamp with time zone,
    completed_at       timestamp with time zone,
    constraint uq_session_lesson_trainee unique (lesson_id, trainee_id)
);
create index idx_session_trainee_state on training_session (trainee_id, state);
create index idx_session_lesson on training_session (lesson_id);

create table assessment (
    id                  uuid primary key,
    session_id          uuid not null references training_session (id),
    mode                varchar(20) not null,
    ai_payload          text not null,
    ai_total            numeric(5,2) not null,
    timing_score        numeric(5,2),
    language_score      numeric(5,2),
    syntax_errors       integer not null default 0,
    teacher_id          uuid references app_user (id),
    teacher_total       numeric(5,2),
    teacher_comment     text,
    teacher_assessed_at timestamp with time zone,
    created_at          timestamp with time zone not null default current_timestamp
);
create index idx_assessment_session on assessment (session_id);
