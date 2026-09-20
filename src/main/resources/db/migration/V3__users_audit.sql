-- Учётные записи трёх ролей, журнал аудита всех действий, настройки.
create table app_user (
    id                 uuid primary key,
    login              varchar(100) not null unique,
    password_hash      varchar(100) not null,
    display_name       varchar(200) not null,
    role               varchar(20) not null,
    workstation_number varchar(10),
    group_id           uuid,
    active             boolean not null default true,
    created_by         uuid,
    created_at         timestamp with time zone not null default current_timestamp,
    updated_at         timestamp with time zone not null default current_timestamp
);

create table audit_event (
    id            uuid primary key,
    actor_user_id uuid,
    actor_login   varchar(100),
    actor_role    varchar(20),
    action        varchar(200) not null,
    resource_type varchar(50),
    resource_id   varchar(100),
    http_status   smallint,
    request_id    uuid,
    client_ip     varchar(64),
    payload       text,
    occurred_at   timestamp with time zone not null default current_timestamp
);
create index idx_audit_actor_time on audit_event (actor_user_id, occurred_at);
create index idx_audit_time on audit_event (occurred_at);

create table app_setting (
    setting_key varchar(100) primary key,
    setting_value text not null,
    updated_by  uuid,
    updated_at  timestamp with time zone not null default current_timestamp
);
insert into app_setting (setting_key, setting_value) values ('audit.retention_days', '180');
insert into app_setting (setting_key, setting_value) values ('telephony.ringing_ms', '300');
insert into app_setting (setting_key, setting_value) values ('telephony.connect_ms', '700');
insert into app_setting (setting_key, setting_value) values ('telephony.acknowledge_ms', '1200');
insert into app_setting (setting_key, setting_value) values ('sla.acceptance_seconds', '30');
insert into app_setting (setting_key, setting_value) values ('sla.processing_seconds', '180');
insert into app_setting (setting_key, setting_value) values ('logging.level', 'INFO');
