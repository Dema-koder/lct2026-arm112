-- Порог нормы занятия: балл, выше которого результат считается нормой.
--
-- Была V10; переномерована в V17 при слиянии ветки экранов аналитики:
-- под номером 10 уже лежит V10__assessment_card.sql. Содержимое не менялось.
alter table lesson add column norm_score integer not null default 60;
alter table lesson add constraint lesson_norm_score_range check (norm_score between 0 and 100);
