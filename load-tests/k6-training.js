import http from "k6/http";
import ws from "k6/ws";
import {check, sleep} from "k6";
import {Rate, Trend} from "k6/metrics";

const base = __ENV.BASE_URL || "http://localhost:8080";
const wsBase = __ENV.WS_URL || base.replace(/^http/, "ws");
const contract = __ENV.CONTRACT_VERSION || "0.3";
const sharedUser = __ENV.USERNAME || "";
const userPrefix = __ENV.USER_PREFIX || "trainee-";
const password = __ENV.PASSWORD || "trainee";
const failures = new Rate("arm112_failures");
const cardLatency = new Trend("arm112_card_burst_ms", true);

export const options = {
  stages: [
    {duration: __ENV.RAMP_UP || "1m", target: Number(__ENV.USERS || 20)},
    {duration: __ENV.HOLD || "3m", target: Number(__ENV.USERS || 20)},
    {duration: "30s", target: 0},
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<750", "p(99)<1500"],
    arm112_failures: ["rate<0.01"],
    arm112_card_burst_ms: ["p(95)<1000"],
  },
};

function headers(token) {
  const result = {"Content-Type": "application/json", "X-Contract-Version": contract};
  if (token) result.Authorization = `Bearer ${token}`;
  return result;
}

export default function () {
  const username = sharedUser || `${userPrefix}${__VU}`;
  const login = http.post(`${base}/api/v1/auth/login`, JSON.stringify({username, password}), {headers: headers()});
  const loggedIn = check(login, {"login 200": (response) => response.status === 200});
  failures.add(!loggedIn);
  if (!loggedIn) { sleep(1); return; }

  const auth = login.json();
  const context = http.get(`${base}/api/v1/trainee/context`, {headers: headers(auth.accessToken)});
  const contextOk = check(context, {"context 200": (response) => response.status === 200});
  failures.add(!contextOk);

  const sessionId = contextOk && context.json("activeSession.id");
  if (sessionId) {
    const started = Date.now();
    const responses = http.batch([
      ["GET", `${base}/api/v1/training-sessions/${sessionId}`, null, {headers: headers(auth.accessToken)}],
      ["GET", `${base}/api/v1/training-sessions/${sessionId}/cards?limit=100`, null, {headers: headers(auth.accessToken)}],
      ["GET", `${base}/api/v1/training-sessions/${sessionId}/events?afterSequence=0`, null, {headers: headers(auth.accessToken)}],
    ]);
    cardLatency.add(Date.now() - started);
    failures.add(!responses.every((response) => response.status === 200));
  }

  const ticket = http.post(`${base}/api/v1/auth/ws-ticket`, null, {headers: headers(auth.accessToken)});
  if (ticket.status === 201) {
    const result = ws.connect(`${wsBase}/ws/v1?ticket=${ticket.json("ticket")}`, {}, socket => {
      socket.setTimeout(() => socket.close(), 1000);
    });
    failures.add(result && result.status !== 101);
  } else {
    failures.add(true);
  }

  const refreshed = http.post(`${base}/api/v1/auth/refresh`,
    JSON.stringify({refreshToken: auth.refreshToken}), {headers: headers()});
  failures.add(!check(refreshed, {"refresh 200": (response) => response.status === 200}));
  if (refreshed.status === 200) {
    http.post(`${base}/api/v1/auth/logout`,
      JSON.stringify({refreshToken: refreshed.json("refreshToken")}), {headers: headers()});
  }
  sleep(Math.random() * 2 + 0.5);
}
