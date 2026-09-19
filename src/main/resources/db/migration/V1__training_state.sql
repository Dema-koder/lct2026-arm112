create table training_state (
    state_key varchar(100) primary key,
    payload text not null,
    revision bigint not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table realtime_event (
    event_id uuid primary key,
    session_id uuid not null,
    sequence_number bigint not null,
    event_type varchar(100) not null,
    occurred_at timestamp with time zone not null,
    server_time timestamp with time zone not null,
    resource_id varchar(200),
    payload text not null,
    constraint uq_realtime_event_sequence unique (session_id, sequence_number)
);

create index idx_realtime_event_replay
    on realtime_event (session_id, sequence_number);
