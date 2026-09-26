-- Очередь фоновых задач (B6 плана): генерация сценариев, персональный разбор.
--
-- Без брокера, на таблице с SELECT ... FOR UPDATE SKIP LOCKED. Причины в порядке веса:
--   1) задача ставится в той же транзакции, что и породившая её запись (оценка сессии),
--      поэтому нет двойной записи: либо и оценка, и задача, либо ничего. С брокером
--      пришлось бы делать outbox — то есть ровно эту же таблицу, только вдобавок;
--   2) пик нагрузки — около 30 задач в момент окончания занятия, за день сотни.
--      Это на три порядка ниже, чем масштаб, где брокер окупается;
--   3) локальный контур: каждый лишний компонент нужно поставить, настроить, бэкапить
--      и восстановить в учебном центре без выхода наружу. Состояние очереди едет
--      в резервную копию вместе с остальными таблицами.
create table job (
    id              uuid primary key,
    type            varchar(40) not null,
    state           varchar(20) not null,
    payload         text not null,
    result          text,
    error           text,
    attempts        integer not null default 0,
    max_attempts    integer not null default 3,
    -- раньше этого момента задачу не берут: пауза между повторами
    next_attempt_at timestamp with time zone not null default current_timestamp,
    -- до какого момента задача считается занятой исполнителем; истекла — задачу можно забрать
    -- заново. Без этого упавший исполнитель оставлял бы задачу в RUNNING навсегда
    lease_until     timestamp with time zone,
    created_by      uuid references app_user (id),
    created_at      timestamp with time zone not null default current_timestamp,
    started_at      timestamp with time zone,
    finished_at     timestamp with time zone
);

-- выборка очередной задачи: по состоянию и времени следующей попытки
create index idx_job_ready on job (state, next_attempt_at);
-- показ задач, поставленных преподавателем, в его интерфейсе
create index idx_job_creator on job (created_by, created_at);
