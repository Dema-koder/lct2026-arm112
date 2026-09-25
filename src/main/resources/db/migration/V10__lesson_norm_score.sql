alter table lesson add column norm_score integer not null default 60;
alter table lesson add constraint lesson_norm_score_range check (norm_score between 0 and 100);
