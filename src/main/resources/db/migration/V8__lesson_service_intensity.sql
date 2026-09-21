-- Служба, за которую играет обучающийся-диспетчер (режим действий), и интенсивность потока карточек/вызовов.
alter table lesson add column service_code varchar(20);
alter table lesson add column intensity varchar(20) not null default 'SEQUENTIAL';
