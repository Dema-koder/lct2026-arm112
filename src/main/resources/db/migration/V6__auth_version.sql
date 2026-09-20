-- Версия авторизации немедленно отзывает ранее выданные JWT после блокировки,
-- смены роли или пароля пользователя.
alter table app_user add column auth_version bigint not null default 1;
