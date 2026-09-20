export const statusLabels: Record<string, string> = {
  RECEIVED: "Добавлена",
  RECEIVED_BY_SERVICE: "Получена службой",
  ACCEPTED: "Принята",
  NOT_ACCEPTED: "Не принята",
  RESPONSE_STARTED: "Начало реагирования",
  ARRIVED: "Прибытие",
  WORK_IN_PROGRESS: "Проведение работ",
  WORK_REFUSED: "Отказ от выполнения работ",
  COMPLETED: "Работы завершены",
};

export const callLabels: Record<string, string> = {
  DIALING: "Набор номера",
  RINGING: "Вызов",
  CONNECTED: "Соединение установлено",
  ACKNOWLEDGED: "Информация принята",
  ENDED: "Звонок завершён",
  NO_ANSWER: "Нет ответа",
  FAILED: "Ошибка соединения",
  CANCELLED: "Отменён",
};

export const actionLabels: Record<string, string> = {
  DELIVERED: "Добавлена",
  RECEIVE: "Получена службой",
  ACCEPT: "Принята",
  DECLINE: "Не принята",
  START_RESPONSE: "Начало реагирования",
  ARRIVE: "Прибытие",
  START_WORK: "Проведение работ",
  REFUSE_WORK: "Отказ от выполнения работ",
  COMPLETE: "Работы завершены",
};

export const kindLabels: Record<string, string> = {
  TRAINING: "Тренировка",
  CHECK: "Проверка",
  EXAM: "Зачёт",
};

export const modeLabels: Record<string, string> = {
  CARD_FILL: "Заполнение карточки",
  CARD_ACTIONS: "Действия с карточкой",
};

export const sourceLabels: Record<string, string> = {
  GENERATED: "Сгенерированные системой",
  TRAINEE_MADE: "Сформированные обучающимися",
  MIXED: "Смешанно",
  TICKET: "Билет",
};

export const lessonStateLabels: Record<string, string> = {
  DRAFT: "Не начато",
  ACTIVE: "Идёт",
  COMPLETED: "Завершено",
};

export const sessionStateLabels: Record<string, string> = {
  PENDING: "Ожидает",
  ACTIVE: "Работает",
  COMPLETED: "Завершил",
};

export const roleLabels: Record<string, string> = {
  ADMIN: "Администратор",
  TEACHER: "Преподаватель",
  TRAINEE: "Обучающийся",
};

export const criterionLabels: Record<string, string> = {
  timing: "Время",
  actions: "Действия",
  communication: "Коммуникация",
  language: "Грамотность",
  address: "Адрес",
  classification: "Тип происшествия",
  services: "Службы",
};

/** Критерии и веса по режимам — те же, что в AssessmentWeights на сервере. */
export function criteriaForMode(mode: string): Array<{ code: string; weight: number }> {
  return mode === "CARD_FILL"
    ? [{ code: "address", weight: 40 }, { code: "classification", weight: 20 }, { code: "services", weight: 20 }, { code: "timing", weight: 15 }, { code: "language", weight: 5 }]
    : [{ code: "timing", weight: 30 }, { code: "actions", weight: 40 }, { code: "communication", weight: 15 }, { code: "language", weight: 15 }];
}

export const categoryLabels: Record<string, string> = {
  FIRE: "Пожары",
  MEDICAL: "Медицина",
  POLICE: "Полиция",
  RESCUE: "Спасение",
  GAS: "Газ",
  UTILITY: "ЖКХ",
  OTHER: "Прочее",
};

export function dateTime(value: string | null | undefined) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).format(new Date(value));
}

export function dateOnly(value: string | null | undefined) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("ru-RU", { day: "2-digit", month: "2-digit", year: "numeric" }).format(new Date(value));
}

export function timeOnly(value: string | null | undefined) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}

export function countdown(deadline: string | null | undefined, now: number) {
  if (!deadline) return "—";
  const seconds = Math.max(0, Math.ceil((new Date(deadline).getTime() - now) / 1000));
  return mmss(seconds);
}

export function elapsed(from: string | null | undefined, now: number) {
  if (!from) return "00:00";
  const seconds = Math.max(0, Math.floor((now - new Date(from).getTime()) / 1000));
  return mmss(seconds);
}

export function mmss(seconds: number) {
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

export function score(value: number | null | undefined) {
  return value === null || value === undefined ? "—" : String(Math.round(value * 10) / 10);
}

export function getMessage(error: unknown) {
  return error instanceof Error ? error.message : "Неизвестная ошибка";
}

export function bytes(value: number) {
  if (value < 1024) return `${value} Б`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} КБ`;
  return `${(value / 1024 / 1024).toFixed(1)} МБ`;
}
