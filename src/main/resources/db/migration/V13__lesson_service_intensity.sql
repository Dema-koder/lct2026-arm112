-- Служба, за которую играет обучающийся-диспетчер (режим действий), и интенсивность потока карточек/вызовов.
--
-- Была V8; переномерована в V13, потому что в main под номером 8 уже лежит
-- V8__realistic_simulation_settings.sql — Flyway не запускается, когда две миграции
-- заявляют одну версию. Содержимое не менялось, зависимостей от V9–V12 нет:
-- те ссылаются на lesson(id), а не на добавляемые здесь колонки.
alter table lesson add column service_code varchar(20);
alter table lesson add column intensity varchar(20) not null default 'SEQUENTIAL';
