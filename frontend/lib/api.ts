export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1";

export const WS_BASE =
  process.env.NEXT_PUBLIC_WS_URL ??
  API_BASE.replace(/^http/, "ws").replace(/\/api\/v1$/, "/ws/v1");

export const CONTRACT_VERSION = "0.3";

// ------------------------------------------------------------------ users / sessions

export type Role = "ADMIN" | "TEACHER" | "TRAINEE";
export type LessonKind = "TRAINING" | "CHECK" | "EXAM";
export type LessonMode = "CARD_FILL" | "CARD_ACTIONS";
export type CardSource = "GENERATED" | "TRAINEE_MADE" | "MIXED";

export type User = {
  id: string;
  displayName: string;
  role: Role;
  login: string;
  workstationNumber: string | null;
  groupId: string | null;
};

export type TrainingSession = {
  id: string;
  mode: LessonMode;
  state: string;
  title: string;
  difficulty: number;
  referenceVersion: string;
  serverTime: string;
  startedAt: string | null;
  completedAt: string | null;
  cardIds: string[];
  draftIds: string[];
  lessonId: string;
  lessonKind: LessonKind;
  pendingScenarios: number;
  ownServiceCode: string;
  intensity: Intensity;
  incomingCall: IncomingCall | null;
  queuedCalls: number;
};

export type Intensity = "SEQUENTIAL" | "LOW" | "MEDIUM" | "HIGH";

export type IncomingCall = { id: string; phone: string | null; callerName: string | null; ringingSince: string; missedCount: number };

export type JournalRow = {
  id: string;
  kind: "OWN" | "BACKGROUND";
  number: string;
  receivedAt: string;
  workstationNumber: string | null;
  incidentTypeLabel: string;
  addressLabel: string | null;
  description: string;
  callerName: string | null;
  services: string[];
  status: string;
};

export type TraineeContext = {
  user: User;
  workstation: { id: string; number: string; label: string };
  serverTime: string;
  activeSession: TrainingSession | null;
};

export type Hint = { field: string; message: string };

// ------------------------------------------------------------------ cards (ДДС)

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
  description: string;
  senderLabel: string;
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

export type TimelineEntry = {
  id: string;
  action: string;
  resultingStatus: string;
  reasonCode: string | null;
  comment: string | null;
  occurredAt: string;
  actorLabel: string;
  actorRole: string;
};

export type IncidentCard = {
  scenarioTitle: string | null;
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
  timeline: TimelineEntry[];
  outboundCalls: OutboundCall[];
  hints: Hint[];
  ownServiceCode: string;
};

// ------------------------------------------------------------------ card fill (оператор 112)

export type FormalAddress = {
  country?: string | null;
  region?: string | null;
  locality?: string | null;
  object?: string | null;
  okrug?: string | null;
  district?: string | null;
  street?: string | null;
  house?: string | null;
  building?: string | null;
  structure?: string | null;
  apartment?: string | null;
  entrance?: string | null;
  floor?: string | null;
  code?: string | null;
  descriptive?: string | null;
};

export type DraftFlags = {
  victims: boolean;
  victimsCount: number | null;
  ambulanceRefused: boolean;
  blocked: boolean;
  noContact: boolean;
  callDropped: boolean;
};

export type SurveyAnswer = { questionId: string; optionId: string | null; text: string | null };
export type DraftService = { code: string; label: string; auto: boolean };

export type CardDraft = {
  id: string;
  sessionId: string;
  scenarioId: string;
  number: string;
  startedAt: string;
  savedAt: string | null;
  deadlineAt: string | null;
  state: "DRAFT" | "SAVED";
  callerText: string;
  phones: { ani: string | null; provided: string | null; onSite: string | null };
  caller: { fullName: string | null; status: string | null };
  address: FormalAddress;
  flags: DraftFlags;
  incidentTypeIds: string[];
  surveyAnswers: SurveyAnswer[];
  description: string;
  services: DraftService[];
  hints: Hint[];
  scenarioTitle: string | null;
  callerAddress: string | null;
  topTypeId: string | null;
};

export type CardDraftPatch = Partial<{
  phones: CardDraft["phones"];
  caller: CardDraft["caller"];
  address: FormalAddress;
  flags: DraftFlags;
  incidentTypeIds: string[];
  surveyAnswers: SurveyAnswer[];
  description: string;
  extraServiceCodes: string[];
  topTypeId: string;
}>;

export type IncidentTypeItem = { id: string; label: string; category: string; frequent: boolean; significant: boolean };
/** Позиция списка «что случилось?» в ПОВ-112 (49 типов верхнего уровня + справки). */
export type TopTypeItem = { id: string; label: string; frequent: boolean };
export type SurveyQuestion = {
  id: string;
  text: string;
  kind: "CHOICE" | "TEXT";
  options: DictionaryItem[];
  /** Вопрос показывается, если выполнено хотя бы одно условие: {вопрос: [допустимые ответы]}. */
  showWhen: Array<Record<string, string[]>>;
  /** Ветка не подтверждена скриншотами заказчика — достроена по смыслу. */
  synthetic: boolean;
};
export type SurveyTree = { topTypeId: string; label: string; questions: SurveyQuestion[]; defaultType: string | null };
export type SurveyCard = { id: string; incidentTypeId: string; questions: SurveyQuestion[] };
export type ServiceItem = {
  code: string;
  label: string;
  fullName: string;
  kind: "EMERGENCY" | "CITY" | "DEPARTMENT" | "DISTRICT" | "OKRUG" | "SETTLEMENT" | "ROADS" | "FEDERAL" | "REGION" | "GROUP";
  okrug: string | null;
  district: string | null;
  settlement: string | null;
};

// ------------------------------------------------------------------ assessment / results

export type AssessmentIssue = {
  code: string;
  severity: string;
  message: string;
  cardId: string | null;
  expected?: unknown;
  actual?: unknown;
};

export type CriterionScore = { code: string; score: number | null; comment: string | null };

export type Assessment = {
  id: string;
  sessionId: string;
  state: string;
  mode: LessonMode;
  totalScore: number | null;
  timingScore: number | null;
  actionsScore: number | null;
  communicationScore: number | null;
  languageScore: number | null;
  addressScore: number | null;
  classificationScore: number | null;
  servicesScore: number | null;
  syntaxErrors: number | null;
  issues: AssessmentIssue[];
  recommendations: string[];
  source: "AI" | "TEACHER";
  aiTotalScore: number | null;
  teacherTotalScore: number | null;
  teacherComment: string | null;
  teacherAssessedAt: string | null;
  aiCriteria: CriterionScore[];
  teacherCriteria: CriterionScore[];
};

export type ResultItem = {
  sessionId: string;
  lessonId: string;
  lessonTitle: string;
  lessonKind: LessonKind;
  mode: LessonMode;
  completedAt: string | null;
  visible: boolean;
  finalTotal: number | null;
  source: "AI" | "TEACHER" | null;
  assessmentId: string | null;
};

export type Rating = { value: number | null; rank: number | null; groupSize: number | null; completedSessions: number | null };

export type Material = {
  id: string;
  teacherId: string;
  title: string;
  fileName: string;
  contentType: string | null;
  sizeBytes: number;
  uploadedAt: string;
  groupIds: string[];
};

// ------------------------------------------------------------------ teacher

export type Group = { id: string; name: string; teacherId: string; teacherName: string | null; members: User[] };

export type Scenario = {
  id: string;
  title: string;
  source: "TICKET" | "GENERATED" | "TRAINEE_MADE";
  category: string;
  difficulty: number;
  callerText: string;
  caller: { fullName: string | null; phone: string | null } | null;
  rawAddress: string | null;
  expectedAddress: FormalAddress | null;
  expectedIncidentTypes: string[];
  expectedServices: string[];
  addressClarified: boolean;
  expectedDecision: string | null;
  expectedDecisionReason: string | null;
  outboundCallRequired: boolean;
  referenceConfirmed: boolean;
  referenceConfirmedAt: string | null;
  createdBy: string | null;
  createdAt: string;
};

export type ScenarioListItem = Pick<Scenario, "id" | "title" | "source" | "category" | "difficulty" | "callerText" | "rawAddress" | "expectedAddress" | "expectedIncidentTypes" | "expectedServices" | "referenceConfirmed" | "createdAt">;

export type ScenarioUpsert = {
  id?: string | null;
  title: string;
  category: string;
  difficulty: number;
  callerText: string;
  caller?: Scenario["caller"];
  rawAddress?: string | null;
  expectedAddress?: FormalAddress | null;
  expectedIncidentTypes?: string[] | null;
  expectedServices?: string[] | null;
  expectedDecision?: string | null;
  expectedDecisionReason?: string | null;
  outboundCallRequired: boolean;
};

export type Lesson = {
  id: string;
  teacherId: string;
  groupId: string | null;
  groupName: string | null;
  title: string;
  kind: LessonKind;
  mode: LessonMode;
  cardSource: CardSource;
  state: "DRAFT" | "ACTIVE" | "COMPLETED";
  scenarioIds: string[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  resultsPublishedAt: string | null;
  sessionCount: number;
  serviceCode: string;
  intensity: Intensity;
};

export type LessonCreate = {
  title: string;
  groupId: string | null;
  kind: LessonKind;
  mode: LessonMode;
  cardSource: CardSource;
  scenarioIds: string[];
  traineeIds: string[];
  serviceCode: string | null;
  intensity: Intensity;
};

export type MonitorRow = {
  sessionId: string;
  traineeId: string;
  traineeName: string;
  workstationNumber: string | null;
  state: string;
  currentStatus: string | null;
  acceptanceOverdue: boolean;
  processingOverdue: boolean;
  startedAt: string | null;
  completedAt: string | null;
  completedCards: number;
  totalCards: number;
  finalTotal: number | null;
};

export type LessonMonitor = { lesson: Lesson; sessions: MonitorRow[] };

export type ReportRow = {
  sessionId: string;
  traineeId: string;
  traineeName: string;
  workstationNumber: string | null;
  timingScore: number | null;
  syntaxErrors: number | null;
  level: number | null;
  aiTotal: number | null;
  teacherTotal: number | null;
  finalTotal: number | null;
  state: string;
};

export type LessonReport = { lesson: Lesson; rows: ReportRow[]; groupRating: number | null };

export type SessionSummary = {
  id: string;
  lessonId: string;
  lessonTitle: string;
  lessonKind: LessonKind | null;
  mode: LessonMode | null;
  state: string;
  startedAt: string | null;
  completedAt: string | null;
};

export type SessionDetail = {
  session: SessionSummary;
  trainee: User | null;
  cards: IncidentCard[];
  drafts: CardDraft[];
  assessment: Assessment | null;
};

// ------------------------------------------------------------------ admin

export type UserAdminView = {
  id: string;
  login: string;
  displayName: string;
  role: Role;
  workstationNumber: string | null;
  groupId: string | null;
  active: boolean;
  createdAt: string;
};

export type AuditEntry = {
  id: string;
  actorUserId: string | null;
  actorLogin: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  httpStatus: number | null;
  requestId: string | null;
  clientIp: string | null;
  payload: string | null;
  occurredAt: string;
};

export type AuditPage = { items: AuditEntry[]; nextCursor: string | null };
export type SystemHealth = { status: string; database: string; openSockets: number; activeSessions: number; version: string; serverTime: string };
export type BackupInfo = { fileName: string; sizeBytes: number; createdAt: string };

// ------------------------------------------------------------------ transport

/** Глобальный обработчик 401: регистрируется корнем приложения и выбрасывает на экран входа. */
let unauthorizedHandler: ((reason: string) => void) | null = null;
export const onUnauthorized = (handler: ((reason: string) => void) | null) => { unauthorizedHandler = handler; };

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

async function request<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-Contract-Version", CONTRACT_VERSION);
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  } catch {
    throw new ApiError(0, "BACKEND_UNAVAILABLE",
      "Нет соединения с учебным сервером. Проверьте, что backend запущен на порту 8080.");
  }

  const text = await response.text();
  let data: unknown = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }
  if (!response.ok) {
    const error = (data as { error?: { code?: string; message?: string; details?: Record<string, unknown> } } | null)?.error;
    if (response.status === 401 && !path.startsWith("/auth/login") && unauthorizedHandler) {
      // токен отозван (блокировка, смена роли/пароля, восстановление копии) — сессия завершена
      unauthorizedHandler(error?.message ?? "Сессия завершена — войдите заново");
    }
    throw new ApiError(response.status, error?.code ?? "REQUEST_FAILED",
      error?.message ?? `Ошибка HTTP ${response.status}`, error?.details);
  }
  return data as T;
}

const commandHeaders = () => ({ "Idempotency-Key": crypto.randomUUID() });
const json = (body: unknown) => JSON.stringify(body);
const q = (params: Record<string, string | number | boolean | null | undefined>) => {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : "";
};

export const api = {
  // auth
  login: (username: string, password: string) =>
    request<{ accessToken: string; expiresAt: string; user: User }>("/auth/login", { method: "POST", body: json({ username, password }) }),
  me: (token: string) => request<User>("/auth/me", {}, token),
  changePassword: (token: string, current: string, next: string) =>
    request<void>("/auth/password", { method: "POST", body: json({ current, next }) }, token),
  wsTicket: (token: string) => request<{ ticket: string; expiresAt: string }>("/auth/ws-ticket", { method: "POST" }, token),

  // trainee
  context: (token: string) => request<TraineeContext>("/trainee/context", {}, token),
  results: (token: string) => request<ResultItem[]>("/trainee/results", {}, token),
  rating: (token: string) => request<Rating>("/trainee/rating", {}, token),
  traineeMaterials: (token: string) => request<Material[]>("/trainee/materials", {}, token),
  materialDownloadUrl: (id: string) => `${API_BASE}/trainee/materials/${id}/download`,
  sessions: (token: string) => request<SessionSummary[]>("/training-sessions", {}, token),
  cards: (token: string, sessionId: string) =>
    request<{ serverTime: string; items: CardListItem[]; nextCursor: string | null }>(`/cards${q({ sessionId, limit: 100 })}`, {}, token),
  card: (token: string, cardId: string) => request<IncidentCard>(`/cards/${cardId}`, {}, token),
  accept: (token: string, cardId: string, action: "ACCEPT" | "DECLINE", comment?: string, reasonCode?: string) =>
    request<IncidentCard>(`/cards/${cardId}/acceptance`, {
      method: "POST", headers: commandHeaders(),
      body: json({ action, reasonCode: reasonCode || null, comment: comment || null, clientOccurredAt: null }),
    }, token),
  react: (token: string, cardId: string, action: "START_RESPONSE" | "ARRIVE" | "START_WORK" | "REFUSE_WORK" | "COMPLETE", comment?: string) =>
    request<IncidentCard>(`/cards/${cardId}/reaction-events`, {
      method: "POST", headers: commandHeaders(),
      body: json({ action, reasonCode: null, comment: comment || null, clientOccurredAt: null }),
    }, token),
  startCall: (token: string, cardId: string, shortNumber: string) =>
    request<OutboundCall>(`/cards/${cardId}/outbound-calls`, { method: "POST", headers: commandHeaders(), body: json({ shortNumber }) }, token),
  call: (token: string, callId: string) => request<OutboundCall>(`/outbound-calls/${callId}`, {}, token),
  endCall: (token: string, callId: string) =>
    request<OutboundCall>(`/outbound-calls/${callId}/end`, { method: "POST", headers: commandHeaders() }, token),
  submit: (token: string, sessionId: string) =>
    request<{ assessmentId: string; state: string }>(`/training-sessions/${sessionId}/submit`, { method: "POST", headers: commandHeaders() }, token),
  assessment: (token: string, assessmentId: string) => request<Assessment>(`/assessments/${assessmentId}`, {}, token),

  // card fill
  drafts: (token: string, sessionId: string) => request<CardDraft[]>(`/card-drafts${q({ sessionId })}`, {}, token),
  cardTypes: (token: string, query?: string) => request<TopTypeItem[]>(`/references/card-types${q({ query })}`, {}, token),
  surveyTree: (token: string, topTypeId: string) => request<SurveyTree>(`/references/survey-trees/${topTypeId}`, {}, token),
  serviceCatalog: (token: string) => request<ServiceItem[]>("/references/services", {}, token),
  journal: (token: string, sessionId: string) => request<{ rows: JournalRow[] }>(`/training-sessions/${sessionId}/journal`, {}, token),
  createDraft: (token: string, sessionId: string) =>
    request<CardDraft>("/card-drafts", { method: "POST", body: json({ sessionId }) }, token),
  patchDraft: (token: string, draftId: string, patch: CardDraftPatch) =>
    request<CardDraft>(`/card-drafts/${draftId}`, { method: "PATCH", body: json(patch) }, token),
  saveDraft: (token: string, draftId: string) =>
    request<CardDraft>(`/card-drafts/${draftId}/save`, { method: "POST", headers: commandHeaders() }, token),
  incidentTypes: (token: string, query?: string) =>
    request<IncidentTypeItem[]>(`/references/incident-types${q({ query })}`, {}, token),
  surveyCard: (token: string, incidentTypeId: string) =>
    request<SurveyCard>(`/references/survey-cards/${encodeURIComponent(incidentTypeId)}`, {}, token),
  references: (token: string) =>
    request<{ referenceVersion: string; checksum: string; services: DictionaryItem[]; reactionReasons: DictionaryItem[] }>("/references", {}, token),

  // teacher
  teacher: {
    scenarios: (token: string, filter: { category?: string; source?: string; confirmed?: boolean } = {}) =>
      request<ScenarioListItem[]>(`/teacher/scenarios${q(filter)}`, {}, token),
    scenario: (token: string, id: string) => request<Scenario>(`/teacher/scenarios/${encodeURIComponent(id)}`, {}, token),
    createScenario: (token: string, body: ScenarioUpsert) =>
      request<Scenario>("/teacher/scenarios", { method: "POST", body: json(body) }, token),
    updateScenario: (token: string, id: string, body: ScenarioUpsert) =>
      request<Scenario>(`/teacher/scenarios/${encodeURIComponent(id)}`, { method: "PUT", body: json(body) }, token),
    confirmReference: (token: string, id: string) =>
      request<Scenario>(`/teacher/scenarios/${encodeURIComponent(id)}/confirm-reference`, { method: "POST" }, token),
    generate: (token: string, category: string, count: number, difficulty: number) =>
      request<Scenario[]>("/teacher/scenarios/generate", { method: "POST", body: json({ category, count, difficulty }) }, token),
    groups: (token: string) => request<Group[]>("/teacher/groups", {}, token),
    lessons: (token: string, state?: string) => request<Lesson[]>(`/teacher/lessons${q({ state })}`, {}, token),
    lesson: (token: string, id: string) => request<Lesson>(`/teacher/lessons/${id}`, {}, token),
    createLesson: (token: string, body: LessonCreate) => request<Lesson>("/teacher/lessons", { method: "POST", body: json(body) }, token),
    startLesson: (token: string, id: string) => request<Lesson>(`/teacher/lessons/${id}/start`, { method: "POST" }, token),
    completeLesson: (token: string, id: string) => request<Lesson>(`/teacher/lessons/${id}/complete`, { method: "POST" }, token),
    publishLesson: (token: string, id: string) => request<Lesson>(`/teacher/lessons/${id}/publish`, { method: "POST" }, token),
    monitor: (token: string, id: string) => request<LessonMonitor>(`/teacher/lessons/${id}/monitor`, {}, token),
    report: (token: string, id: string) => request<LessonReport>(`/teacher/lessons/${id}/report`, {}, token),
    reportCsv: async (token: string, id: string) => {
      const response = await fetch(`${API_BASE}/teacher/lessons/${id}/report.csv`, {
        headers: { Authorization: `Bearer ${token}`, "X-Contract-Version": CONTRACT_VERSION },
      });
      if (!response.ok) throw new ApiError(response.status, "REQUEST_FAILED", "Не удалось выгрузить отчёт");
      return response.blob();
    },
    session: (token: string, sessionId: string) => request<SessionDetail>(`/teacher/sessions/${sessionId}`, {}, token),
    assess: (token: string, sessionId: string, total: number | null, comment: string, criteria: CriterionScore[] = []) =>
      request<Assessment>(`/teacher/sessions/${sessionId}/assessment`, {
        method: "PUT", body: json({ total, comment: comment || null, criteria }),
      }, token),
    materials: (token: string) => request<Material[]>("/teacher/materials", {}, token),
    uploadMaterial: (token: string, file: File, title: string, groupIds: string[]) => {
      const form = new FormData();
      form.set("file", file);
      form.set("title", title);
      for (const id of groupIds) form.append("groupIds", id);
      return request<Material>("/teacher/materials", { method: "POST", body: form }, token);
    },
    deleteMaterial: (token: string, id: string) => request<void>(`/teacher/materials/${id}`, { method: "DELETE" }, token),
  },

  // admin
  admin: {
    users: (token: string, role?: string) => request<UserAdminView[]>(`/admin/users${q({ role })}`, {}, token),
    createUser: (token: string, body: { login: string; password: string; displayName: string; role: Role; workstationNumber: string | null; groupId: string | null }) =>
      request<UserAdminView>("/admin/users", { method: "POST", body: json(body) }, token),
    updateUser: (token: string, id: string, body: { displayName: string; role: Role; workstationNumber: string | null; groupId: string | null }) =>
      request<UserAdminView>(`/admin/users/${id}`, { method: "PUT", body: json(body) }, token),
    block: (token: string, id: string) => request<UserAdminView>(`/admin/users/${id}/block`, { method: "POST" }, token),
    unblock: (token: string, id: string) => request<UserAdminView>(`/admin/users/${id}/unblock`, { method: "POST" }, token),
    resetPassword: (token: string, id: string, password: string) =>
      request<void>(`/admin/users/${id}/reset-password`, { method: "POST", body: json({ password }) }, token),
    groups: (token: string) => request<Group[]>("/admin/groups", {}, token),
    createGroup: (token: string, name: string, teacherId: string) =>
      request<Group>("/admin/groups", { method: "POST", body: json({ name, teacherId }) }, token),
    updateGroup: (token: string, id: string, name: string, teacherId: string) =>
      request<Group>(`/admin/groups/${id}`, { method: "PUT", body: json({ name, teacherId }) }, token),
    settings: (token: string) => request<Record<string, string>>("/admin/settings", {}, token),
    updateSettings: (token: string, patch: Record<string, string>) =>
      request<Record<string, string>>("/admin/settings", { method: "PUT", body: json(patch) }, token),
    audit: (token: string, filter: { role?: string; action?: string; cursor?: string; limit?: number } = {}) =>
      request<AuditPage>(`/admin/audit${q(filter)}`, {}, token),
    health: (token: string) => request<SystemHealth>("/admin/system/health", {}, token),
    log: (token: string, lines = 200) => request<{ lines: string[] }>(`/admin/system/log${q({ lines })}`, {}, token),
    backups: (token: string) => request<BackupInfo[]>("/admin/backups", {}, token),
    createBackup: (token: string) => request<BackupInfo>("/admin/backups", { method: "POST" }, token),
    restore: (token: string, fileName: string) =>
      request<void>(`/admin/backups/${encodeURIComponent(fileName)}/restore`, { method: "POST", body: json({ confirm: "RESTORE" }) }, token),
  },
};
