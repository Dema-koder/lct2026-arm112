import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

const src = (path) => readFile(new URL(path, import.meta.url), "utf8");

test("renders the application shell", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="ru">/i);
  assert.match(html, /<title>Учебный тренажёр 112<\/title>/i);
  assert.match(html, /Подключение к учебному серверу/);
});

test("keeps the UI aligned with backend contract v0.3", async () => {
  const [api, dds, fill, teacher, admin, page, journal] = await Promise.all([
    src("../lib/api.ts"),
    src("../app/components/dds/DdsWorkspace.tsx"),
    src("../app/components/fill/FillWorkspace.tsx"),
    src("../app/components/teacher/Lessons.tsx"),
    src("../app/components/admin/AdminShell.tsx"),
    src("../app/page.tsx"),
    src("../app/components/journal/IncidentJournal.tsx"),
  ]);

  assert.match(api, /CONTRACT_VERSION = "0\.3"/);
  assert.match(api, /\/auth\/login/);
  assert.match(api, /\/outbound-calls/);
  assert.match(api, /\/card-drafts/);
  assert.match(api, /\/teacher\/lessons/);
  assert.match(api, /\/admin\/users/);

  // три роли — три рабочих места; отозванный токен сразу выбрасывает на вход
  assert.match(page, /case "TEACHER":/);
  assert.match(page, /case "ADMIN":/);
  assert.match(api, /export const onUnauthorized/);
  assert.match(page, /onUnauthorized\(/);

  // экран ДДС: общий журнал, статусы, таймеры для всех активных статусов, своя плитка службы
  assert.match(journal, /Поиск происшествий/);
  assert.match(dds, /IncidentJournal/);
  assert.match(dds, /Начало реагирования/);
  assert.match(dds, /Завершить занятие/);
  assert.match(dds, /\["RECEIVED", "RECEIVED_BY_SERVICE"\]\.includes\(card\.status\)/);
  assert.match(dds, /\["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"\]\.includes\(card\.status\)/);
  assert.match(dds, /ownServiceCode/);
  assert.match(dds, /"own" : "foreign"/);

  // оператор 112: главный экран с журналом, входящий вызов, «уточнить адрес», журнал сессии
  assert.match(fill, /Входящий вызов/);
  assert.match(fill, /cardTypes/);
  assert.match(fill, /surveyTree/);
  assert.match(fill, /serviceCatalog/);
  assert.match(api, /\/references\/card-types/);
  assert.match(fill, /уточнить адрес у заявителя/);
  assert.match(fill, /callerAddress/);
  assert.match(api, /\/journal/);

  // экран оператора 112: описательный адрес, «что случилось», счётчик 0 / 1999, единственная кнопка «сохранить»
  assert.match(fill, /Описательный адрес/);
  assert.match(fill, /Что случилось\?/);
  assert.match(fill, /\/ 1999/);
  assert.match(fill, /className="save-button"/);

  // преподаватель: виды занятий и публикация зачёта; администратор: подтверждение восстановления
  assert.match(teacher, /Опубликовать результаты/);
  assert.match(teacher, /Оценка преподавателя по критериям/);
  assert.match(teacher, /scenarioTitle/);
  assert.match(teacher, /Интенсивность/);
  assert.match(teacher, /Служба обучающегося/);
  assert.match(teacher, /непрофильные/);
  assert.match(teacher, /EXAM/);
  assert.match(admin, /RESTORE/);
  assert.match(admin, /Управление сервисами/);
  assert.match(api, /\/admin\/system\/services/);
  assert.match(admin, /Поиск по ФИО, логину или номеру АРМ/);
  assert.match(api, /\/groups\/\$\{id\}\/members/);
  assert.match(admin, /Что это и когда перезапускать/);
  assert.match(admin, /Действие или адрес API/);
  assert.match(admin, /ARM112_ALERT_CALL_GATEWAY_TOKEN/);
});
