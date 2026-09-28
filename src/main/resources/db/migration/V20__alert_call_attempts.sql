-- Аудит всех аварийных и тестовых телефонных вызовов.
create table alert_call_attempt (
    id              uuid primary key,
    retry_of_id     uuid,
    service_id      varchar(50),
    trigger_type    varchar(30) not null,
    recipient       varchar(100) not null,
    message         varchar(1000) not null,
    status          varchar(30) not null,
    answered        boolean,
    attempt_number  integer not null,
    recipient_order integer not null,
    gateway_call_id varchar(200),
    error_message   varchar(500),
    requested_at    timestamp with time zone not null,
    updated_at      timestamp with time zone not null
);

create index idx_alert_call_attempt_time on alert_call_attempt (requested_at desc);
create index idx_alert_call_attempt_gateway on alert_call_attempt (gateway_call_id);
