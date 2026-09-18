export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1";

export const WS_BASE =
  process.env.NEXT_PUBLIC_WS_URL ??
  API_BASE.replace(/^http/, "ws").replace(/\/api\/v1$/, "/ws/v1");

export type User = {
  id: string;
  displayName: string;
  role: "TRAINEE";
};

export type TrainingSession = {
  id: string;
  mode: "CARD_ACTIONS";
  state: string;
  title: string;
  difficulty: number;
  referenceVersion: string;
  serverTime: string;
  startedAt: string | null;
  completedAt: string | null;
  cardIds: string[];
};

export type TraineeContext = {
  user: User;
  workstation: { id: string; number: string; label: string };
  serverTime: string;
  activeSession: TrainingSession | null;
};

export type CardSla = {
  acceptanceDeadlineAt: string;
  processingDeadlineAt: string | null;
  acceptanceOverdue: boolean;
  processingOverdue: boolean;
};

export type CardListItem = {
  id: string;
  number: string;
  receivedAt: string;
  incidentTypeLabel: string;
  addressLabel: string;
  status: string;
  allowedActions: string[];
  sla: CardSla;
};

export type DictionaryItem = { id: string; code: string; label: string };

export type CallTarget = {
  id: string;
  shortNumber: string;
  displayName: string;
  organization: string;
  voice: "MALE" | "FEMALE";
};

export type OutboundCall = {
  id: string;
  cardId: string;
  target: CallTarget;
  state: string;
  startedAt: string;
  connectedAt: string | null;
  endedAt: string | null;
  media: {
    ringbackUrl: string | null;
    answerUrl: string | null;
    acknowledgementUrl: string | null;
    expiresAt: string | null;
  };
};

export type IncidentCard = {
  id: string;
  sessionId: string;
  number: string;
  receivedAt: string;
  source: string;
  senderLabel: string;
  status: string;
  allowedActions: string[];
  sla: CardSla;
  caller: { fullName: string | null; phone: string | null; relation: string | null };
  address: {
    raw: string;
    region: string | null;
    district: string | null;
    street: string | null;
    house: string | null;
    landmark: string | null;
    latitude: number | null;
    longitude: number | null;
  };
  description: string;
  incidentType: DictionaryItem;
  features: string[];
  assignedServices: DictionaryItem[];
  scenarioRequirements: {
    outboundCallRequired: boolean;
    requiredTargetIds: string[];
    commentRequiredOnCompletion: boolean;
  };
  callTargets: CallTarget[];
  timeline: Array<{
    id: string;
    action: string;
    resultingStatus: string;
    reasonCode: string | null;
    comment: string | null;
    occurredAt: string;
    actorLabel: string;
  }>;
  outboundCalls: OutboundCall[];
};

export type Assessment = {
  id: string;
  sessionId: string;
  state: string;
  totalScore: number | null;
  timingScore: number | null;
  actionsScore: number | null;
  communicationScore: number | null;
  languageScore: number | null;
  issues: Array<{ code: string; severity: string; message: string }>;
};

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details?: Record<string, unknown>,
  ) {
    super(message);
  }
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  token?: string | null,
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-Contract-Version", "0.2");
  if (options.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  } catch {
    throw new ApiError(
      0,
      "BACKEND_UNAVAILABLE",
      "Нет соединения с учебным сервером. Проверьте, что backend запущен на порту 8080.",
    );
  }

  const text = await response.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!response.ok) {
    const error = (data as {
      error?: { code?: string; message?: string; details?: Record<string, unknown> };
    } | null)?.error;
    throw new ApiError(
      response.status,
      error?.code ?? "REQUEST_FAILED",
      error?.message ?? `Ошибка HTTP ${response.status}`,
      error?.details,
    );
  }
  return data as T;
}

const commandHeaders = () => ({ "Idempotency-Key": crypto.randomUUID() });

export const api = {
  login: (username: string, password: string) =>
    request<{ accessToken: string; expiresAt: string; user: User }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  me: (token: string) => request<User>("/auth/me", {}, token),
  context: (token: string) => request<TraineeContext>("/trainee/context", {}, token),
  cards: (token: string, sessionId: string) =>
    request<{ serverTime: string; items: CardListItem[]; nextCursor: string | null }>(
      `/cards?sessionId=${encodeURIComponent(sessionId)}&limit=100`,
      {},
      token,
    ),
  card: (token: string, cardId: string) =>
    request<IncidentCard>(`/cards/${cardId}`, {}, token),
  accept: (token: string, cardId: string, action: "ACCEPT" | "DECLINE", comment?: string) =>
    request<IncidentCard>(
      `/cards/${cardId}/acceptance`,
      {
        method: "POST",
        headers: commandHeaders(),
        body: JSON.stringify({ action, reasonCode: null, comment: comment || null, clientOccurredAt: null }),
      },
      token,
    ),
  react: (
    token: string,
    cardId: string,
    action: "START_RESPONSE" | "REFUSE_WORK" | "COMPLETE",
    comment?: string,
  ) =>
    request<IncidentCard>(
      `/cards/${cardId}/reaction-events`,
      {
        method: "POST",
        headers: commandHeaders(),
        body: JSON.stringify({ action, reasonCode: null, comment: comment || null, clientOccurredAt: null }),
      },
      token,
    ),
  startCall: (token: string, cardId: string, shortNumber: string) =>
    request<OutboundCall>(
      `/cards/${cardId}/outbound-calls`,
      {
        method: "POST",
        headers: commandHeaders(),
        body: JSON.stringify({ shortNumber }),
      },
      token,
    ),
  call: (token: string, callId: string) =>
    request<OutboundCall>(`/outbound-calls/${callId}`, {}, token),
  endCall: (token: string, callId: string) =>
    request<OutboundCall>(
      `/outbound-calls/${callId}/end`,
      { method: "POST", headers: commandHeaders() },
      token,
    ),
  submit: (token: string, sessionId: string) =>
    request<{ assessmentId: string; state: string }>(
      `/training-sessions/${sessionId}/submit`,
      { method: "POST", headers: commandHeaders() },
      token,
    ),
  assessment: (token: string, assessmentId: string) =>
    request<Assessment>(`/assessments/${assessmentId}`, {}, token),
  wsTicket: (token: string) =>
    request<{ ticket: string; expiresAt: string }>("/auth/ws-ticket", { method: "POST" }, token),
};
