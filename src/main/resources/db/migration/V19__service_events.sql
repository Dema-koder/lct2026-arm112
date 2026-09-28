-- История переключений и автоматического мониторинга сервисов.
create table service_event (
    id             uuid primary key,
    service_id     varchar(50) not null,
    event_type     varchar(30) not null,
    previous_state varchar(20),
    current_state  varchar(20) not null,
    action         varchar(20),
    outcome        varchar(20) not null,
    message        varchar(500),
    actor_user_id  uuid,
    actor_login    varchar(100),
    notified       boolean not null default false,
    occurred_at    timestamp with time zone not null default current_timestamp
);

create index idx_service_event_service_time on service_event (service_id, occurred_at desc);
create index idx_service_event_time on service_event (occurred_at desc);
