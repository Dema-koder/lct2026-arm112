-- Методические материалы преподавателя; файлы лежат на диске, здесь только описание.
create table material (
    id           uuid primary key,
    teacher_id   uuid not null references app_user (id),
    title        varchar(200) not null,
    file_name    varchar(300) not null,
    content_type varchar(100),
    size_bytes   bigint not null,
    storage_path varchar(500) not null,
    uploaded_at  timestamp with time zone not null default current_timestamp
);
create table material_group (
    material_id uuid not null references material (id),
    group_id    uuid not null references training_group (id),
    primary key (material_id, group_id)
);
