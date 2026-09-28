-- Версионируемая коррекция оценок ИИ по подтверждённым оценкам преподавателей.
create table assessment_calibration_model (
    id              uuid primary key,
    mode            varchar(20) not null,
    version         integer not null,
    parameters      text not null,
    assessments     integer not null,
    mae_before      numeric(8,3),
    mae_after       numeric(8,3),
    active          boolean not null default false,
    created_by      uuid not null references app_user (id),
    created_at      timestamp with time zone not null default current_timestamp,
    activated_at    timestamp with time zone,
    deactivated_at  timestamp with time zone,
    constraint uq_calibration_mode_version unique (mode, version)
);
create index idx_calibration_mode_active on assessment_calibration_model (mode, active);

-- raw_ai_payload нужен, чтобы следующая версия училась на исходной оценке,
-- а не на результате предыдущей коррекции.
alter table assessment add column raw_ai_payload text;
alter table assessment add column calibration_model_id uuid references assessment_calibration_model (id);
alter table assessment add column calibration_version integer;
