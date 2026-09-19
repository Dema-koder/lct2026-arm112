"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type Assessment, type CardListItem, type IncidentCard, type OutboundCall, type TraineeContext } from "../../../lib/api";
import { actionLabels, callLabels, countdown, dateTime, elapsed, statusLabels, timeOnly } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, TopStrip, useAction, useClock, useNotice, useSocket } from "../common";
import { AssessmentView } from "../trainee/Results";

function journalDeadline(card: CardListItem) {
  if (["RECEIVED", "RECEIVED_BY_SERVICE"].includes(card.status)) {
    return card.sla.acceptanceDeadlineAt;
  }
  if (["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"].includes(card.status)) {
    return card.sla.processingDeadlineAt;
  }
  return null;
}

type Props = {
  token: string;
  context: TraineeContext;
  onLogout: () => void;
  onReload: () => Promise<void>;
  nav: React.ReactNode;
  label: string;
};

/** Экран диспетчера ДДС (режим действий с карточкой) — журнал происшествий и карточка по кадрам dds-*.png. */
export function DdsWorkspace({ token, context, onLogout, onReload, nav, label }: Props) {
  const [cards, setCards] = useState<CardListItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [card, setCard] = useState<IncidentCard | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [search, setSearch] = useState("");
  const now = useClock();
  const [commentDialog, setCommentDialog] = useState<"DECLINE" | "REFUSE_WORK" | "COMPLETE" | null>(null);
  const [comment, setComment] = useState("");
  const [callDialog, setCallDialog] = useState(false);
  const [shortNumber, setShortNumber] = useState("1102");
  const [activeCall, setActiveCall] = useState<OutboundCall | null>(null);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [serviceMenu, setServiceMenu] = useState<string | null>(null);
  const [working, run] = useAction(setError);

  const session = context.activeSession!;
  const sessionId = session.id;

  const loadCard = useCallback(async (cardId: string) => {
    const value = await api.card(token, cardId);
    setCard(value);
    setActiveCall([...value.outboundCalls].at(-1) ?? null);
  }, [token]);

  const loadCards = useCallback(async () => {
    const page = await api.cards(token, sessionId);
    setCards(page.items);
  }, [token, sessionId]);

  useEffect(() => {
    void run(loadCards);
  }, [loadCards, run]);

  useSocket(token, async (event) => {
    if (event.type === "training.session_state_changed" || event.type === "training.session_started") {
      await onReload();
      return;
    }
    await loadCards().catch(() => undefined);
    if (selectedId) await loadCard(selectedId).catch(() => undefined);
    if (event.type === "card.created") setNotice("Новая карточка в журнале");
  });

  useEffect(() => {
    if (!activeCall || ["ENDED", "FAILED", "NO_ANSWER", "CANCELLED", "ACKNOWLEDGED"].includes(activeCall.state)) return;
    const timer = window.setInterval(async () => {
      try {
        const updated = await api.call(token, activeCall.id);
        setActiveCall(updated);
      } catch { /* сокет или следующий опрос восстановят состояние */ }
    }, 500);
    return () => window.clearInterval(timer);
  }, [activeCall, token]);

  const filteredCards = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return cards;
    return cards.filter((item) =>
      [item.number, item.incidentTypeLabel, item.addressLabel, statusLabels[item.status]]
        .some((value) => value?.toLowerCase().includes(query)),
    );
  }, [cards, search]);

  const openCard = (id: string) => {
    setSelectedId(id);
    void run(() => loadCard(id));
  };

  const refreshAfter = async (updated: IncidentCard, message: string) => {
    setCard(updated);
    setNotice(message);
    await loadCards();
  };

  const accept = () => card && run(async () => refreshAfter(await api.accept(token, card.id, "ACCEPT"), "Карточка принята"));

  const submitCommentAction = () => card && commentDialog && run(async () => {
    const updated = commentDialog === "DECLINE"
      ? await api.accept(token, card.id, "DECLINE", comment)
      : await api.react(token, card.id, commentDialog, comment);
    await refreshAfter(updated, statusLabels[updated.status] ?? "Действие выполнено");
    setCommentDialog(null);
    setComment("");
  });

  const runReaction = (action: "START_RESPONSE" | "ARRIVE" | "START_WORK") => card && run(async () => {
    const updated = await api.react(token, card.id, action);
    await refreshAfter(updated, statusLabels[updated.status] ?? "Действие выполнено");
  });

  const startCall = () => card && run(async () => {
    const call = await api.startCall(token, card.id, shortNumber);
    setActiveCall(call);
    setCallDialog(false);
    setNotice(`Вызов ${shortNumber}`);
    await loadCard(card.id);
  });

  const endCall = () => activeCall && run(async () => {
    setActiveCall(await api.endCall(token, activeCall.id));
    if (card) await loadCard(card.id);
    setNotice("Звонок завершён");
  });

  const submitSession = () => run(async () => {
    const response = await api.submit(token, sessionId);
    try {
      setAssessment(await api.assessment(token, response.assessmentId));
    } catch {
      setNotice("Занятие сдано — результат появится после проверки преподавателем");
    }
    await onReload();
  });

  return (
    <main className="arm-shell">
      <TopStrip label={label} onLogout={onLogout} nav={nav} />

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
          session={session}
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
          workstation={context.workstation.number}
        />
      )}

      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />

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
        <Modal title="Результат занятия" onClose={() => setAssessment(null)} wide>
          <AssessmentView assessment={assessment} />
          <div className="dialog-actions"><button className="primary" onClick={() => setAssessment(null)}>Закрыть</button></div>
        </Modal>
      )}
    </main>
  );
}

function IncidentJournal({ cards, search, setSearch, onOpen, now, context, loading, onSubmit, session }: {
  cards: CardListItem[];
  search: string;
  setSearch: (value: string) => void;
  onOpen: (id: string) => void;
  now: number;
  context: TraineeContext;
  loading: boolean;
  onSubmit: () => void;
  session: TraineeContext["activeSession"] & object;
}) {
  const current = new Date(now);
  const canSubmit = session.state === "ACTIVE" && session.pendingScenarios === 0
    && cards.every((c) => ["NOT_ACCEPTED", "WORK_REFUSED", "COMPLETED"].includes(c.status));
  return (
    <section className="journal">
      <div className="journal-heading">
        <div className="journal-search">
          <div className="search-line">
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск происшествий" aria-label="Поиск происшествий" />
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
          <small>{context.workstation.label}</small>
        </div>
      </div>
      <div className="list-area">
        <div className="list-title">
          <b>Список происшествий⌃</b>
          <span>
            {session.pendingScenarios > 0 && <small>ещё карточек в очереди: {session.pendingScenarios}</small>}
            {canSubmit && <button className="submit-session" onClick={onSubmit}>Завершить занятие</button>}
            ● уведомления　 <select aria-label="Фильтр"><option>выберите что показать</option></select>
          </span>
        </div>
        <div className="incident-columns"><span>Связи</span><span>ЧС</span><span>Опер.</span><span>АРМ</span><span>Номер</span><span>Дата</span><span>Время</span><span>Тип происшествия</span><span>Постр.</span><span>Адрес</span><span>Статус службы</span></div>
        {cards.length === 0 ? (
          <div className="empty-list">{loading ? "Обновление…" : "Карточки не найдены"}</div>
        ) : cards.map((item) => {
          const deadline = journalDeadline(item);
          return <button key={item.id} className={`incident-row ${item.sla.acceptanceOverdue || item.sla.processingOverdue ? "overdue" : ""}`} onClick={() => onOpen(item.id)}>
            <span>⌄　◆　⚡</span><span>0</span><span>0</span><span>{context.workstation.number}</span><b>{item.number.replace(/\D/g, "").slice(-8)}</b>
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

function CardWorkspace({ card, now, activeCall, serviceMenu, setServiceMenu, onBack, onAccept, onDialog, onReaction, onCall, onEndCall, working, workstation }: {
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
  workstation: string;
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
        <div className="card-number"><b>Происшествие {card.number.replace(/\D/g, "").slice(-8)}</b><small>созд. {dateTime(card.receivedAt)}<br />Опер., АРМ {workstation}</small></div>
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

      {card.hints.length > 0 && (
        <div className="hint-strip">
          {card.hints.map((hint) => <span key={hint.field + hint.message}>💡 {hint.message}</span>)}
        </div>
      )}

      <div className="card-columns">
        <div className="caller-pane">
          <b>{card.address.region ?? "Москва"}{card.address.district ? `, ${card.address.district}` : ""}</b>
          <span>{card.address.raw}</span>
          <div className="message"><b>{dateTime(card.receivedAt)}　{card.senderLabel}</b><p>{card.description}</p><small>Телефон: {card.caller.phone ?? "не указан"} · {card.caller.relation ?? "отношение не указано"}</small></div>
        </div>
        <div className="incident-pane">
          <h2>Происшествие {card.incidentType.label}</h2>
          <b>{card.features.length > 0 ? card.features.join(" · ") : card.address.landmark ?? ""}</b>
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
        <div className="service-tiles">
          {card.assignedServices.map((service) => {
            const own = service.code === card.ownServiceCode;
            const expanded = serviceMenu === service.id;
            return (
              <div key={service.id} className={`service-tile ${expanded ? "active" : ""} ${own ? "" : "foreign"}`}>
                <button
                  className="tile-body"
                  title={own ? undefined : "Статус другой службы — только просмотр"}
                  onClick={() => { if (!own) return; setServiceMenu(expanded ? null : service.id); setStatusList(false); }}
                >
                  <i className="tile-chevron">{expanded ? "⌄" : "⌃"}</i>
                  <b>{service.label}</b>
                  <span>{own ? `${lastEvent ? timeOnly(lastEvent.occurredAt) : "—"} ${statusLabels[card.status] ?? card.status}` : `${timeOnly(card.receivedAt)} Добавлена`}</span>
                </button>
                {expanded && own && (
                  <button className="tile-pencil" title="Проставить статус реагирования" onClick={() => setStatusList(!statusList)}>✎</button>
                )}
              </div>
            );
          })}
        </div>
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
