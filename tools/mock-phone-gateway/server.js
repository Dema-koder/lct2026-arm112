const http = require("node:http");
const { randomUUID, timingSafeEqual } = require("node:crypto");

const port = Number(process.env.PORT || 8090);
const token = process.env.GATEWAY_TOKEN || "local-mock-phone-token";
const callbackBaseUrl = process.env.CALLBACK_BASE_URL || "http://backend:8080/api/v1/integrations/phone/calls";
const allowedScenarios = new Set(["ACCEPTED", "ANSWERED", "NOT_ANSWERED", "FAILED", "UNAVAILABLE"]);
let configuration = {
  scenario: allowedScenarios.has(process.env.DEFAULT_SCENARIO) ? process.env.DEFAULT_SCENARIO : "ANSWERED",
  delayMs: boundedDelay(process.env.DEFAULT_DELAY_MS || 1000),
};

function boundedDelay(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(100, Math.min(30000, Math.round(parsed))) : 1000;
}

function authorized(request) {
  const supplied = (request.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const expectedBuffer = Buffer.from(token);
  const suppliedBuffer = Buffer.from(supplied);
  return expectedBuffer.length === suppliedBuffer.length && timingSafeEqual(expectedBuffer, suppliedBuffer);
}

function json(response, status, body) {
  const content = JSON.stringify(body);
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(content) });
  response.end(content);
}

async function readJson(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > 64 * 1024) throw new Error("request is too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

async function callback(callId, scenario, delayMs) {
  if (scenario === "ACCEPTED" || scenario === "UNAVAILABLE") return;
  await new Promise((resolve) => setTimeout(resolve, delayMs));
  const body = scenario === "ANSWERED"
    ? { status: "ANSWERED", answered: true }
    : scenario === "NOT_ANSWERED"
      ? { status: "NOT_ANSWERED", answered: false }
      : { status: "FAILED", answered: false, errorMessage: "Тестовая ошибка mock-шлюза" };
  try {
    const response = await fetch(`${callbackBaseUrl}/${encodeURIComponent(callId)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", "X-ARM112-Gateway-Token": token },
      body: JSON.stringify(body),
    });
    if (!response.ok) console.error(`callback ${callId} returned HTTP ${response.status}`);
  } catch (error) {
    console.error(`callback ${callId} failed: ${error.message}`);
  }
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
  if (request.method === "GET" && url.pathname === "/health") {
    return json(response, 200, { status: "UP" });
  }
  if (!authorized(request)) return json(response, 401, { error: "invalid gateway token" });

  try {
    if (request.method === "GET" && url.pathname === "/scenario") {
      return json(response, 200, configuration);
    }
    if (request.method === "PUT" && url.pathname === "/scenario") {
      const body = await readJson(request);
      const scenario = String(body.scenario || "").toUpperCase();
      if (!allowedScenarios.has(scenario)) {
        return json(response, 422, { error: `unknown scenario: ${scenario}` });
      }
      configuration = { scenario, delayMs: boundedDelay(body.delayMs) };
      return json(response, 200, configuration);
    }
    if (request.method === "POST" && url.pathname === "/calls") {
      const body = await readJson(request);
      if (!body.phoneNumber || !body.message || !body.attemptId) {
        return json(response, 422, { error: "phoneNumber, message and attemptId are required" });
      }
      const snapshot = { ...configuration };
      if (snapshot.scenario === "UNAVAILABLE") {
        return json(response, 503, { error: "Тестовая недоступность mock-шлюза" });
      }
      const callId = `mock-${randomUUID()}`;
      void callback(callId, snapshot.scenario, snapshot.delayMs);
      return json(response, 202, { callId, status: "ACCEPTED", answered: null });
    }
    return json(response, 404, { error: "not found" });
  } catch (error) {
    return json(response, 400, { error: error.message });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`ARM-112 mock phone gateway is listening on ${port}`);
});
