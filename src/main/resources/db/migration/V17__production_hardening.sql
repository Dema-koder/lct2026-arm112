-- Надёжность запросов, отзыв refresh-токенов и защита входа.
create table idempotency_record (
    operation         varchar(240) not null,
    idempotency_key   uuid not null,
    request_signature text not null,
    response_type     varchar(300),
    response_payload  text,
    created_at        timestamp with time zone not null default current_timestamp,
    expires_at        timestamp with time zone not null,
    primary key (operation, idempotency_key)
);
create index idx_idempotency_expiry on idempotency_record (expires_at);

create table auth_refresh_token (
    token_hash   varchar(64) primary key,
    user_id      uuid not null references app_user(id) on delete cascade,
    auth_version bigint not null,
    issued_at    timestamp with time zone not null default current_timestamp,
    expires_at   timestamp with time zone not null,
    revoked_at   timestamp with time zone
);
create index idx_refresh_user on auth_refresh_token (user_id, expires_at);

create table login_throttle (
    throttle_key    varchar(300) primary key,
    failures        integer not null default 0,
    window_started  timestamp with time zone not null,
    blocked_until   timestamp with time zone,
    updated_at      timestamp with time zone not null default current_timestamp
);
create index idx_login_throttle_updated on login_throttle (updated_at);

alter table realtime_event add column delivered_at timestamp with time zone;
alter table realtime_event add column delivery_attempts integer not null default 0;

create table realtime_event_sequence (
    session_id    uuid primary key,
    last_sequence bigint not null
);

insert into realtime_event_sequence (session_id, last_sequence)
select session_id, max(sequence_number)
  from realtime_event
 group by session_id;
