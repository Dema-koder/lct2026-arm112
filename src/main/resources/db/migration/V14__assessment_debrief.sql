-- Персональный разбор занятия для обучающегося (задача D5 плана).
--
-- Отдельной таблицей, а не колонкой в assessment: разбор появляется позже самой
-- оценки, может не появиться вовсе (модель недоступна), может быть перегенерирован.
-- Держать всё это в колонке значило бы смешивать состояние оценки с состоянием
-- фоновой задачи.
create table assessment_debrief (
    assessment_id uuid primary key references assessment (id),
    text          text not null,
    -- LLM или RULES: по этому полю видно, писала модель или правила,
    -- и не нужно гадать, почему текст выглядит иначе
    source        varchar(20) not null,
    created_at    timestamp with time zone not null default current_timestamp
);
