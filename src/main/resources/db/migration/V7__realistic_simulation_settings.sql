-- Управляемые задержки учебной симуляции. Шкала 25% сохраняет порядок реальных
-- действий, но позволяет увидеть прибытие нескольких служб за время демонстрации.
insert into app_setting (setting_key, setting_value)
values ('simulation.card_open_ms', '2500');

insert into app_setting (setting_key, setting_value)
values ('simulation.card_arrival_ms', '8000');

insert into app_setting (setting_key, setting_value)
values ('simulation.service_time_scale_percent', '25');
