"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  API_BASE,
  WS_BASE,
  ApiError,
  api,
  type Assessment,
  type CardListItem,
  type IncidentCard,
  type OutboundCall,
  type TraineeContext,
} from "../lib/api";

const statusLabels: Record<string, string> = {
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

const callLabels: Record<string, string> = {
  DIALING: "Набор номера",
  RINGING: "Вызов",
  CONNECTED: "Соединение установлено",
  ACKNOWLEDGED: "Информация принята",
  ENDED: "Звонок завершён",
  NO_ANSWER: "Нет ответа",
  FAILED: "Ошибка соединения",
  CANCELLED: "Отменён",
};

const actionLabels: Record<string, string> = {
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

function dateTime(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

function timeOnly(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

function countdown(deadline: string | null, now: number) {
  if (!deadline) return "—";
  const seconds = Math.max(0, Math.ceil((new Date(deadline).getTime() - now) / 1000));
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function elapsed(from: string, now: number) {
  const seconds = Math.max(0, Math.floor((now - new Date(from).getTime()) / 1000));
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function journalDeadline(card: CardListItem) {
  if (["RECEIVED", "RECEIVED_BY_SERVICE"].includes(card.status)) {
    return card.sla.acceptanceDeadlineAt;
  }
  if (["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"].includes(card.status)) {
    return card.sla.processingDeadlineAt;
  }
  return null;
}

function getMessage(error: unknown) {
  return error instanceof Error ? error.message : "Неизвестная ошибка";
}

export default function Home() {
  const [token, setToken] = useState<string | null>(null);
  const [restoring, setRestoring] = useState(true);

  useEffect(() => {
    const saved = sessionStorage.getItem("arm112-token");
    if (!saved) {
      queueMicrotask(() => setRestoring(false));
      return;
    }
    api.me(saved)
      .then(() => setToken(saved))
      .catch(() => sessionStorage.removeItem("arm112-token"))
      .finally(() => setRestoring(false));
  }, []);

  const onLogin = (newToken: string) => {
    sessionStorage.setItem("arm112-token", newToken);
    setToken(newToken);
  };

  const logout = useCallback(() => {
    sessionStorage.removeItem("arm112-token");
    setToken(null);
  }, []);

  if (restoring) {
    return <div className="boot-screen">Подключение к учебному серверу…</div>;
  }
  return token ? <Workspace token={token} onLogout={logout} /> : <Login onLogin={onLogin} />;
}

function Login({ onLogin }: { onLogin: (token: string) => void }) {
  const [username, setUsername] = useState("trainee");
  const [password, setPassword] = useState("trainee");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const response = await api.login(username, password);
      onLogin(response.accessToken);
    } catch (err) {
      setError(getMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-screen">
      <div className="skyline skyline-back">
        {[14, 24, 13, 31, 19, 25, 14, 34, 20, 29, 12].map((height, index) => (
          <i key={index} style={{ height: `${height}vh` }} />
        ))}
      </div>
      <div className="skyline skyline-front">
        {[18, 12, 23, 15, 31, 19, 11, 21].map((height, index) => (
          <i key={index} style={{ height: `${height}vh` }} />
        ))}
      </div>
      <div className="helicopter" aria-hidden="true">🚁</div>
      <form className="login-panel" onSubmit={submit}>
        <div className="login-title"><b>112</b><span>ВХОД В СИСТЕМУ</span></div>
        <label>
          <span>логин:</span>
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
        </label>
        <label>
          <span>пароль:</span>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
        </label>
        <button type="submit" disabled={loading}>{loading ? "ПОДКЛЮЧЕНИЕ…" : "ВОЙТИ"}</button>
        {error && <p className="login-error">{error}</p>}
        <div className="support-copy">
          Техподдержка<br />
          +7 (495) 197-89-81<br />
          (многоканальный)<br />
          <a href="mailto:hd-112@mos.ru">hd-112@mos.ru</a>
        </div>
      </form>
      <div className="login-version">
        ЛЦТ 2026 · учебный контур ·{" "}
        <a href={`${API_BASE.replace("/api/v1", "")}/swagger-ui.html`} target="_blank" rel="noreferrer">Swagger</a>
      </div>
    </main>
  );
}

function Workspace({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [context, setContext] = useState<TraineeContext | null>(null);
  const [cards, setCards] = useState<CardListItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [card, setCard] = useState<IncidentCard | null>(null);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [search, setSearch] = useState("");
  const [now, setNow] = useState(0);
  const [commentDialog, setCommentDialog] = useState<"DECLINE" | "REFUSE_WORK" | "COMPLETE" | null>(null);
  const [comment, setComment] = useState("");
  const [callDialog, setCallDialog] = useState(false);
  const [shortNumber, setShortNumber] = useState("1102");
  const [activeCall, setActiveCall] = useState<OutboundCall | null>(null);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [serviceMenu, setServiceMenu] = useState<string | null>(null);

  const sessionId = context?.activeSession?.id ?? null;

  const loadCard = useCallback(async (cardId: string) => {
    const value = await api.card(token, cardId);
    setCard(value);
    const latest = [...value.outboundCalls].at(-1) ?? null;
    setActiveCall(latest);
  }, [token]);

  const loadWorkspace = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const nextContext = await api.context(token);
      setContext(nextContext);
      if (nextContext.activeSession) {
        const page = await api.cards(token, nextContext.activeSession.id);
        setCards(page.items);
      }
      setError("");
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onLogout();
      else setError(getMessage(err));
    } finally {
      if (!silent) setLoading(false);
    }
  }, [token, onLogout]);

  useEffect(() => {
    void Promise.resolve().then(() => loadWorkspace());
  }, [loadWorkspace]);
  useEffect(() => {
    queueMicrotask(() => setNow(Date.now()));
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    if (!sessionId) return;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | undefined;
    let closed = false;
    const connect = async () => {
      try {
        const { ticket } = await api.wsTicket(token);
        if (closed) return;
        socket = new WebSocket(`${WS_BASE}?ticket=${encodeURIComponent(ticket)}`);
        socket.onmessage = async (event) => {
          const message = JSON.parse(event.data);
          if (message.type === "system.connected") return;
          await loadWorkspace(true);
          if (selectedId) await loadCard(selectedId).catch(() => undefined);
        };
        socket.onclose = () => {
          if (!closed) reconnectTimer = window.setTimeout(connect, 1800);
        };
      } catch {
        if (!closed) reconnectTimer = window.setTimeout(connect, 3000);
      }
    };
    connect();
    return () => {
      closed = true;
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [token, sessionId, selectedId, loadWorkspace, loadCard]);

  useEffect(() => {
    if (!activeCall || ["ENDED", "FAILED", "NO_ANSWER", "CANCELLED"].includes(activeCall.state)) return;
    const timer = window.setInterval(async () => {
      try {
        const updated = await api.call(token, activeCall.id);
        setActiveCall(updated);
        if (selectedId) await loadCard(selectedId);
      } catch { /* WebSocket or next poll will recover. */ }
    }, 500);
    return () => window.clearInterval(timer);
  }, [activeCall, token, selectedId, loadCard]);

  const filteredCards = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return cards;
    return cards.filter((item) =>
      [item.number, item.incidentTypeLabel, item.addressLabel, statusLabels[item.status]]
        .some((value) => value?.toLowerCase().includes(query)),
    );
  }, [cards, search]);

  const openCard = async (id: string) => {
    setSelectedId(id);
    setWorking(true);
    try {
      await loadCard(id);
      setError("");
    } catch (err) {
      setError(getMessage(err));
    } finally {
      setWorking(false);
    }
  };

  const refreshAfter = async (updated: IncidentCard, message: string) => {
    setCard(updated);
    setNotice(message);
    await loadWorkspace(true);
  };

  const accept = async () => {
    if (!card) return;
    setWorking(true);
    try {
      await refreshAfter(await api.accept(token, card.id, "ACCEPT"), "Карточка принята");
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  const submitCommentAction = async () => {
    if (!card || !commentDialog) return;
    setWorking(true);
    try {
      const updated = commentDialog === "DECLINE"
        ? await api.accept(token, card.id, "DECLINE", comment)
        : await api.react(token, card.id, commentDialog, comment);
      await refreshAfter(updated, statusLabels[updated.status] ?? "Действие выполнено");
      setCommentDialog(null);
      setComment("");
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  const runReaction = async (action: "START_RESPONSE" | "ARRIVE" | "START_WORK") => {
    if (!card) return;
    setWorking(true);
    try {
      const updated = await api.react(token, card.id, action);
      await refreshAfter(updated, statusLabels[updated.status] ?? "Действие выполнено");
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  const startCall = async () => {
    if (!card) return;
    setWorking(true);
    try {
      const call = await api.startCall(token, card.id, shortNumber);
      setActiveCall(call);
      setCallDialog(false);
      setNotice(`Вызов ${shortNumber}`);
      await loadCard(card.id);
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  const endCall = async () => {
    if (!activeCall) return;
    setWorking(true);
    try {
      const ended = await api.endCall(token, activeCall.id);
      setActiveCall(ended);
      if (card) await loadCard(card.id);
      setNotice("Звонок завершён");
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  const submitSession = async () => {
    if (!sessionId) return;
    setWorking(true);
    try {
      const response = await api.submit(token, sessionId);
      setAssessment(await api.assessment(token, response.assessmentId));
      await loadWorkspace(true);
    } catch (err) { setError(getMessage(err)); }
    finally { setWorking(false); }
  };

  if (loading) return <div className="boot-screen">Загрузка рабочего места ДДС…</div>;

  return (
    <main className="arm-shell">
      <header className="top-strip">
        <div className="product-name">ГБУ Система 112</div>
        <button className="user-chip" onClick={onLogout} title="Выйти из системы">
          {context?.user.displayName} · АРМ {context?.workstation.number} <b>×</b>
        </button>
      </header>

      {!card ? (
        <IncidentJournal
          cards={filteredCards}
          search={search}
          setSearch={setSearch}
          onOpen={openCard}
          now={now}
          context={context}
          loading={working}
          onSubmit={submitSession}
          sessionState={context?.activeSession?.state ?? ""}
        />
      ) : (
        <CardWorkspace
          card={card}
          now={now}
          activeCall={activeCall}
          serviceMenu={serviceMenu}
          setServiceMenu={setServiceMenu}
          onBack={() => { setCard(null); setSelectedId(null); }}
          onAccept={accept}
          onDialog={(value) => { setComment(""); setCommentDialog(value); }}
          onReaction={runReaction}
          onCall={() => setCallDialog(true)}
          onEndCall={endCall}
          working={working}
        />
      )}

      {error && (
        <div className="error-banner" role="alert">
          <b>Ошибка:</b> {error}
          <button onClick={() => setError("")} aria-label="Закрыть">×</button>
        </div>
      )}
      {notice && <div className="notice-toast">✓ {notice}</div>}

      {commentDialog && (
        <div className="status-form" role="dialog" aria-label="Проставление статуса реагирования">
          <span className="status-form-status">
            {{ DECLINE: "Не принята", REFUSE_WORK: "Отказ от выполнения работ", COMPLETE: "Работы завершены" }[commentDialog]}
          </span>
          <input
            autoFocus
            className="status-form-comment"
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder="Комментарий — основание и результат действий"
          />
          <button className="ok" title="Сохранить статус" disabled={working || !comment.trim()} onClick={submitCommentAction}>✓</button>
          <button className="cancel" title="Отмена" onClick={() => setCommentDialog(null)}>✕</button>
        </div>
      )}

      {callDialog && card && (
        <Modal title="Исходящий вызов" onClose={() => setCallDialog(false)}>
          <p className="dialog-copy">Выберите должностное лицо или введите короткий номер.</p>
          <div className="target-list">
            {card.callTargets.map((target) => (
              <button key={target.id} className={shortNumber === target.shortNumber ? "selected" : ""} onClick={() => setShortNumber(target.shortNumber)}>
                <b>{target.shortNumber}</b><span>{target.displayName}<small>{target.organization}</small></span>
              </button>
            ))}
          </div>
          <label className="dialog-label">Короткий номер</label>
          <input className="number-input" value={shortNumber} onChange={(event) => setShortNumber(event.target.value.replace(/\D/g, "").slice(0, 4))} />
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setCallDialog(false)}>Отмена</button>
            <button className="call-button" disabled={working || !/^\d{3,4}$/.test(shortNumber)} onClick={startCall}>☎ Вызвать</button>
          </div>
        </Modal>
      )}

      {assessment && (
        <Modal title="Результат занятия" onClose={() => setAssessment(null)}>
          <div className="score-circle">{Math.round(assessment.totalScore ?? 0)}<small>баллов</small></div>
          <div className="score-grid">
            <span>Время <b>{assessment.timingScore ?? 0}</b></span>
            <span>Действия <b>{assessment.actionsScore ?? 0}</b></span>
            <span>Коммуникация <b>{assessment.communicationScore ?? 0}</b></span>
            <span>Язык <b>{assessment.languageScore ?? 0}</b></span>
          </div>
          {assessment.issues.map((issue) => <p key={issue.code} className="assessment-issue">{issue.message}</p>)}
          <div className="dialog-actions"><button className="primary" onClick={() => setAssessment(null)}>Закрыть</button></div>
        </Modal>
      )}
    </main>
  );
}

function IncidentJournal({ cards, search, setSearch, onOpen, now, context, loading, onSubmit, sessionState }: {
  cards: CardListItem[];
  search: string;
  setSearch: (value: string) => void;
  onOpen: (id: string) => void;
  now: number;
  context: TraineeContext | null;
  loading: boolean;
  onSubmit: () => void;
  sessionState: string;
}) {
  const current = new Date(now);
  return (
    <section className="journal">
      <div className="journal-heading">
        <div className="journal-search">
          <div className="search-line">
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Поиск происшествий"
              aria-label="Поиск происшествий"
            />
            <span className="magnifier" aria-hidden="true">⌕</span>
          </div>
          <div className="search-meta">
            <small>расширенный по параметрам⌄</small>
            <button onClick={() => setSearch("")}>сбросить</button>
          </div>
        </div>
        <div className="digital-clock">
          <b>{new Intl.DateTimeFormat("ru-RU", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(current)}</b>
          <strong>{new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit" }).format(current)}</strong>
          <span>:{String(current.getSeconds()).padStart(2, "0")}</span>
          <small>{context?.workstation.label}</small>
        </div>
      </div>
      <div className="list-area">
        <div className="list-title">
          <b>Список происшествий⌃</b>
          <span>
            {sessionState === "ACTIVE" && (
              <button className="submit-session" onClick={onSubmit}>Завершить занятие</button>
            )}
            ● уведомления　 <select aria-label="Фильтр"><option>выберите что показать</option></select>
          </span>
        </div>
        <div className="incident-columns"><span>Связи</span><span>ЧС</span><span>Опер.</span><span>АРМ</span><span>Номер</span><span>Дата</span><span>Время</span><span>Тип происшествия</span><span>Постр.</span><span>Адрес</span><span>Статус службы</span></div>
        {cards.length === 0 ? (
          <div className="empty-list">{loading ? "Обновление…" : "Карточки не найдены"}</div>
        ) : cards.map((item) => {
          const deadline = journalDeadline(item);
          return <button key={item.id} className={`incident-row ${item.sla.acceptanceOverdue || item.sla.processingOverdue ? "overdue" : ""}`} onClick={() => onOpen(item.id)}>
            <span>⌄　◆　⚡</span><span>0</span><span>0</span><span>12</span><b>{item.number.replace(/\D/g, "").slice(-8)}</b>
            <span>{new Date(item.receivedAt).toLocaleDateString("ru-RU")}</span><strong>{timeOnly(item.receivedAt)}</strong>
            <b title={item.incidentTypeLabel}>{item.incidentTypeLabel}</b>
            <span>Нет</span>
            <b title={item.addressLabel}>{item.addressLabel}</b>
            <span title={statusLabels[item.status] ?? item.status}>{statusLabels[item.status] ?? item.status}</span>
            <em title={item.description}>
              <span className="descr-label">Описание:</span>
              <span className="descr-meta">{dateTime(item.receivedAt)} {item.senderLabel}　-</span>
              <span className="descr-text">{item.description}</span>
            </em>
            {deadline && <i>{countdown(deadline, now)}</i>}
          </button>;
        })}
        <div className="pagination">Страница: 1　 Записей на странице: 10　 <b>1–{cards.length} из {cards.length}</b>　‹　›</div>
      </div>
    </section>
  );
}

function CardWorkspace({ card, now, activeCall, serviceMenu, setServiceMenu, onBack, onAccept, onDialog, onReaction, onCall, onEndCall, working }: {
  card: IncidentCard;
  now: number;
  activeCall: OutboundCall | null;
  serviceMenu: string | null;
  setServiceMenu: (value: string | null) => void;
  onBack: () => void;
  onAccept: () => void;
  onDialog: (value: "DECLINE" | "REFUSE_WORK" | "COMPLETE") => void;
  onReaction: (action: "START_RESPONSE" | "ARRIVE" | "START_WORK") => void;
  onCall: () => void;
  onEndCall: () => void;
  working: boolean;
}) {
  const callActive = activeCall && !["ENDED", "FAILED", "NO_ANSWER", "CANCELLED"].includes(activeCall.state);
  const lastEvent = card.timeline.at(-1);
  const awaitingAcceptance = card.status === "RECEIVED" || card.status === "RECEIVED_BY_SERVICE";
  const [statusList, setStatusList] = useState(false);
  return (
    <section className="card-workspace">
      <div className="phone-strip">
        <button className="back-button" onClick={onBack}>‹</button>
        <div className="phone-state"><b>☎</b><span>{activeCall ? callLabels[activeCall.state] : "Отключение"}<small>{activeCall?.target.displayName ?? "записи звонков　 список SMS"}</small></span></div>
        <div className="phone-slot"><b>☎</b><span>{activeCall?.target.shortNumber ?? "АОН"}</span></div>
        <div className="phone-slot"><b>☎</b><span>{activeCall?.target.organization ?? "предоставленный"}</span></div>
        <div className="phone-slot"><b>☎</b><span>телефон на место</span></div>
        <div className="card-number"><b>Происшествие {card.number.replace(/\D/g, "").slice(-8)}</b><small>созд. {dateTime(card.receivedAt)}<br />Опер., АРМ 12</small></div>
        <div className={`card-timer ${card.sla.acceptanceOverdue || card.sla.processingOverdue ? "overdue" : ""}`}>
          <b>{elapsed(card.receivedAt, now)}</b>
          <span>минут</span><span>секунд</span>
          <small>
            {awaitingAcceptance
              ? `принять за ${countdown(card.sla.acceptanceDeadlineAt, now)}`
              : `отработать за ${countdown(card.sla.processingDeadlineAt, now)}`}
          </small>
        </div>
        <button className="view-button">просмотр</button>
        <button className="back-list" onClick={onBack}>архив/список</button>
      </div>

      <div className="card-summary-row">
        <span>ФИО заявителя: <b>{card.caller.fullName ?? "не указано"}</b></span>
        <span>Пострадавшие: нет　 Отказ от скорой: нет　 Заблокированные: нет</span>
      </div>

      <div className="card-columns">
        <div className="caller-pane">
          <b>{card.address.region}, {card.address.district ?? "ТАО, Вороновское"}</b>
          <span>{card.address.raw}</span>
          <div className="message"><b>{dateTime(card.receivedAt)}　{card.senderLabel}</b><p>{card.description}</p><small>Телефон: {card.caller.phone ?? "не указан"} · {card.caller.relation ?? "отношение не указано"}</small></div>
        </div>
        <div className="incident-pane">
          <h2>Происшествие {card.incidentType.code === "FIRE_GARBAGE_CONTAINER" ? "101" : card.incidentType.code}</h2>
          <b>{card.features.join(" · ")}</b>
          <p>Класс: <strong>{card.incidentType.label.toLowerCase()}</strong>;</p>
          <p>[ВИС] Класс: <span>—</span></p>
          <div className="timeline">
            {card.timeline.map((event) => (
              <div key={event.id}>
                <em>{event.actorLabel}</em>
                <time>{dateTime(event.occurredAt)}</time>
                <b>{actionLabels[event.action] ?? event.action}</b>
                <span>{event.comment ? `❯ ${event.comment}` : ""}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="service-dock">
        <button className="services-label">Службы:</button>
        {card.assignedServices.map((service) => {
          const expanded = serviceMenu === service.id;
          return (
            <div key={service.id} className={`service-tile ${expanded ? "active" : ""}`}>
              <button
                className="tile-body"
                onClick={() => { setServiceMenu(expanded ? null : service.id); setStatusList(false); }}
              >
                <i className="tile-chevron">{expanded ? "⌄" : "⌃"}</i>
                <b>{/^\d+$/.test(service.code) ? `Служба ${service.code}` : service.label}</b>
                <span>{lastEvent ? timeOnly(lastEvent.occurredAt) : "—"} {statusLabels[card.status] ?? card.status}</span>
              </button>
              {expanded && (
                <button
                  className="tile-pencil"
                  title="Проставить статус реагирования"
                  onClick={() => setStatusList(!statusList)}
                >
                  ✎
                </button>
              )}
            </div>
          );
        })}
        <div className="service-spacer" />
        <button className="dock-icon" title="Сообщения">!</button><button className="dock-icon" onClick={onBack}>×</button>

        {serviceMenu && statusList && (
          <div className="service-menu">
            <div className="menu-status"><span>Статус реагирования</span><b>{statusLabels[card.status] ?? card.status}</b></div>
            {card.allowedActions.includes("ACCEPT") && <button onClick={onAccept} disabled={working}>✓ Принята</button>}
            {card.allowedActions.includes("DECLINE") && <button onClick={() => onDialog("DECLINE")} disabled={working}>× Не принята</button>}
            {card.allowedActions.includes("START_RESPONSE") && <button onClick={() => onReaction("START_RESPONSE")} disabled={working}>› Начало реагирования</button>}
            {card.allowedActions.includes("ARRIVE") && <button onClick={() => onReaction("ARRIVE")} disabled={working}>› Прибытие</button>}
            {card.allowedActions.includes("START_WORK") && <button onClick={() => onReaction("START_WORK")} disabled={working}>› Проведение работ</button>}
            {["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"].includes(card.status) && !callActive && (
              <button onClick={onCall} disabled={working}>☎ Исходящий звонок</button>
            )}
            {callActive && <button className="hangup" onClick={onEndCall} disabled={working}>☎ Завершить звонок</button>}
            {card.allowedActions.includes("REFUSE_WORK") && <button onClick={() => onDialog("REFUSE_WORK")} disabled={working}>× Отказ от выполнения работ</button>}
            {card.allowedActions.includes("COMPLETE") && <button onClick={() => onDialog("COMPLETE")} disabled={working}>✓ Работы завершены</button>}
          </div>
        )}
      </div>
    </section>
  );
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}>
      <section className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header><b>{title}</b><button onClick={onClose} aria-label="Закрыть">×</button></header>
        <div className="modal-body">{children}</div>
      </section>
    </div>
  );
}
