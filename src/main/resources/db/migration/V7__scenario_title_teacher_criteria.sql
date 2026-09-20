-- Название сценария, редактируемое преподавателем (в списках вместо id),
-- и оценки преподавателя по критериям рядом с оценкой ИИ.
alter table scenario add column title varchar(200);
alter table assessment add column teacher_payload text;
