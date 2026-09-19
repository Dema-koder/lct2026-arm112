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

test("renders the ARM-112 application shell", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="ru">/i);
  assert.match(html, /<title>АРМ ДДС — учебный тренажёр 112<\/title>/i);
  assert.match(html, /Подключение к учебному серверу/);
});

test("keeps the UI aligned with backend contract v0.2", async () => {
  const [api, page] = await Promise.all([
    readFile(new URL("../lib/api.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
  ]);

  assert.match(api, /headers\.set\("X-Contract-Version", "0\.2"\)/);
  assert.match(api, /\/auth\/login/);
  assert.match(api, /\/outbound-calls/);
  assert.match(page, /Поиск происшествий/);
  assert.match(page, /Начало реагирования/);
  assert.match(page, /Завершить занятие/);
  assert.match(page, /\["RECEIVED", "RECEIVED_BY_SERVICE"\]\.includes\(card\.status\)/);
  assert.match(page, /\["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"\]\.includes\(card\.status\)/);
});
