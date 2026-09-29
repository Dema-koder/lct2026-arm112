-- Сдвиг демо-занятий на прошедшие даты: кривая обучения должна растянуться на недели.
begin;

-- Занятия по порядку проведения раскладываются на прошедшие недели: последнее — 4 дня назад,
-- между занятиями 3,5 дня, начало в 10:00 по Москве. Запускать сразу после demo_data.py.
create temp table plan as
select id as lesson_id,
       (date_trunc('day', now() at time zone 'Europe/Moscow') - interval '4 days'
        - (count(*) over () - row_number() over (order by started_at)) * interval '3 days 12 hours'
        + interval '10 hours') at time zone 'Europe/Moscow' as target
  from lesson where started_at is not null;

create temp table shift as
select p.lesson_id, p.target - l.started_at as d from plan p join lesson l on l.id = p.lesson_id;

create temp table sess as
select s.id as session_id, s.lesson_id, sh.d from training_session s join shift sh on sh.lesson_id = s.lesson_id;

update lesson l set created_at = created_at + sh.d, started_at = started_at + sh.d,
       completed_at = completed_at + sh.d, results_published_at = results_published_at + sh.d
  from shift sh where sh.lesson_id = l.id;
update training_session s set created_at = created_at + x.d, started_at = started_at + x.d, completed_at = completed_at + x.d
  from sess x where x.session_id = s.id;
update assessment a set created_at = created_at + x.d, teacher_assessed_at = teacher_assessed_at + x.d
  from sess x where x.session_id = a.session_id;
update assessment_issue i set created_at = created_at + sh.d from shift sh where sh.lesson_id = i.lesson_id;
update assessment_card c set created_at = created_at + sh.d from shift sh where sh.lesson_id = c.lesson_id;
update assessment_debrief b set created_at = b.created_at + x.d
  from assessment a join sess x on x.session_id = a.session_id where a.id = b.assessment_id;
update realtime_event e set occurred_at = occurred_at + x.d, server_time = server_time + x.d,
       delivered_at = delivered_at + x.d
  from sess x where x.session_id = e.session_id;

-- состояние тренировки: метки времени внутри JSON (черновики, звонки, таймлайн)
do $$
declare r record; m text[]; p text;
begin
  for r in select t.state_key, t.payload, x.d from training_state t join sess x on x.session_id::text = t.state_key loop
    p := r.payload;
    for m in select distinct regexp_matches(r.payload, '"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z)"', 'g') loop
      p := replace(p, '"' || m[1] || '"',
                   '"' || to_char((m[1]::timestamptz + r.d) at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '"');
    end loop;
    update training_state set payload = p, created_at = created_at + r.d, updated_at = updated_at + r.d
     where state_key = r.state_key;
  end loop;
end $$;

select l.title, l.started_at at time zone 'Europe/Moscow' from lesson l order by l.started_at;
commit;
