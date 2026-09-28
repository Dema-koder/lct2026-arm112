# Автоматическая выкладка

Production доступен по адресу `http://103.112.71.71`. Swagger UI и динамический OpenAPI в production отключены;
зафиксированный контракт доступен в репозитории, а для ручной проверки используйте локальный Swagger.

Workflow [`.github/workflows/ci-cd.yml`](../.github/workflows/ci-cd.yml) запускается:

- на каждый pull request в `main` — backend- и frontend-проверки;
- на каждый push в `main`, включая merge pull request — проверки, сборка двух Docker-образов, публикация в GHCR и выкладка на сервер;
- вручную через **Actions → CI/CD → Run workflow**.

Сборка выполняется на GitHub runner, а не на production-сервере. Сервер получает готовые образы, применяет [`compose.production.yaml`](../compose.production.yaml), ждёт успешный health check и при ошибке возвращается на предыдущие образы.

## GitHub Secrets

В репозитории настроен secret `DEPLOY_SSH_KEY`. Это отдельный SSH-ключ только для автоматической выкладки. Пароли и приватные ключи в Git не добавляются.

## Файлы на сервере

Приложение располагается в `/opt/arm112`:

- `.env` — production-пароли и ключ JWT, права `600`;
- `release.env` — адреса образов конкретного Git commit;
- `compose.production.yaml` — production Compose;
- `deploy/nginx.conf` и `deploy/deploy.sh` — reverse proxy и сценарий выкладки.

Данные PostgreSQL и загруженные материалы находятся в Docker volumes и не удаляются при обычной выкладке.
Резервные копии дополнительно пишутся в `${ARM112_OFFSITE_BACKUP_DIR:-/opt/arm112/offsite-backups}`.
Для защиты от потери VPS этот путь должен быть точкой монтирования отдельного диска или NFS,
а не обычным каталогом того же сервера.

## Аварийный звонок

В локальной среде отдельная настройка не нужна: обычный `compose.yaml` запускает mock-шлюз с двумя
тестовыми номерами. Администратор выбирает сценарий и задержку в разделе **Сервисы**, запускает
тестовый звонок и видит callback и эскалацию в истории. В production-конфигурации mock-сервиса нет.

Для боевого голосового оповещения используется только внутренний шлюз организации:

Для голосового оповещения дежурного добавьте в серверный `/opt/arm112/.env`:

```dotenv
ARM112_ALERT_CALL_GATEWAY_URL=https://internal-pbx.example/api/calls
ARM112_ALERT_CALL_GATEWAY_TOKEN=change-me
ARM112_ALERT_PHONE_NUMBERS=+74950000000,+74950000001,+74950000002
```

Номера перечисляются в порядке эскалации: основной, затем резервные. Старый одиночный параметр
`ARM112_ALERT_PHONE_NUMBER` продолжает поддерживаться, если список не задан.

Шлюз должен принимать `POST` с заголовком `Authorization: Bearer <token>` и JSON
`{"phoneNumber":"...","message":"...","source":"ARM-112","attemptId":"..."}`. Успешный ответ —
любой HTTP-код `2xx`. Если шлюз возвращает JSON, рекомендуется передавать
`{"callId":"pbx-123","status":"ACCEPTED","answered":null}` — тогда приложение сможет связать
последующие изменения статуса с попыткой.

После завершения звонка шлюз вызывает
`PUT /api/v1/integrations/phone/calls/{callId}` с заголовком
`X-ARM112-Gateway-Token: <тот же токен>` и телом, например
`{"status":"ANSWERED","answered":true}`. Допустимы статусы `ACCEPTED`, `ANSWERED`,
`NOT_ANSWERED`, `FAILED`. Без обратного вызова в истории останется статус «Шлюз принял», а ответ
абонента будет обозначен как ожидающий подтверждения.
При `NOT_ANSWERED` или `FAILED` приложение автоматически передаёт следующий номер из цепочки.
Рекомендуется использовать внутренний шлюз УПАТС/VoIP организации, доступный только из серверного
сегмента. При пустых параметрах звонки отключены; токен и номер не возвращаются через API и не
показываются в интерфейсе. Настройку можно проверить кнопкой «Проверить звонок» в разделе «Сервисы»;
там же доступны история попыток и ручной повтор.

## Проверка и диагностика

```bash
curl http://103.112.71.71/health
curl http://103.112.71.71/actuator/health
ssh root@103.112.71.71
cd /opt/arm112
docker compose --env-file .env --env-file release.env -f compose.production.yaml ps
docker compose --env-file .env --env-file release.env -f compose.production.yaml logs --tail=200
```

Выкладка использует HTTP, потому что домен пока не указан. После привязки домена нужно добавить HTTPS-сертификат и заменить `ARM112_ALLOWED_ORIGINS` на `https://<домен>`.
