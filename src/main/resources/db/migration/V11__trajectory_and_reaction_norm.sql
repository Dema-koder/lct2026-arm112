-- Траектория заполнения карточки в разрезе, пригодном для запросов.
--
-- Сессионного времени «сохранено минус начато» мало: оно говорит, что обучающийся
-- не уложился, но не говорит где именно встал. Эти колонки отвечают на вопрос
-- «на чём» — на поиске адреса, на выборе типа или на колебаниях между типами
-- (docs/assessment/DASHBOARDS.md §3.4, блок «время внутри карточки»).
alter table assessment_card add column seconds_to_address bigint;
alter table assessment_card add column seconds_to_type bigint;
alter table assessment_card add column type_changes integer;
alter table assessment_card add column idle_seconds bigint;

-- Минимальный правдоподобный интервал между статусами реагирования.
-- Меньше — значит статусы проставлены не глядя: карточку физически не читали.
-- Вынесено в настройки, потому что заказчик просил настраиваемую точность (q-and-a.md §6).
insert into app_setting (setting_key, setting_value) values ('sla.min_reaction_seconds', '5');
