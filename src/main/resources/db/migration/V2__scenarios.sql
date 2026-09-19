-- Сценарии (билеты, сгенерированные, сформированные обучающимися) с эталоном в JSON.
create table scenario (
    id                     varchar(64) primary key,
    source                 varchar(20) not null,
    category               varchar(20) not null,
    difficulty             smallint not null default 5,
    payload                text not null,
    reference_confirmed_by uuid,
    reference_confirmed_at timestamp with time zone,
    created_by             uuid,
    created_at             timestamp with time zone not null default current_timestamp,
    updated_at             timestamp with time zone not null default current_timestamp
);
create index idx_scenario_category on scenario (category, difficulty);
create index idx_scenario_source on scenario (source);
