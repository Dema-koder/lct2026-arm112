# Автоматическая выкладка

Production доступен по адресу `http://103.112.71.71`. Swagger UI после выкладки: `http://103.112.71.71/swagger-ui.html`.

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
