"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, type Assessment, type CardListItem, type IncidentCard, type OutboundCall, type TraineeContext } from "../../../lib/api";
import { actionLabels, callLabels, cardFlags, countdown, dateTime, displayCardNumber, elapsed, flagLabel, statusLabels, timeOnly } from "../../../lib/format";
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

type StatusAction = "ACCEPT" | "DECLINE" | "START_RESPONSE" | "ARRIVE" | "START_WORK" | "REFUSE_WORK" | "COMPLETE";

const STATUS_OPTIONS: Array<{ action: StatusAction; label: string; needsComment: boolean }> = [
  { action: "ACCEPT", label: "Принята", needsComment: false },
  { action: "DECLINE", label: "Не принята", needsComment: true },
  { action: "START_RESPONSE", label: "Начало реагирования", needsComment: false },
  { action: "ARRIVE", label: "Прибытие", needsComment: false },
  { action: "START_WORK", label: "Проведение работ", needsComment: false },
  { action: "REFUSE_WORK", label: "Отказ от выполнения работ", needsComment: true },
  { action: "COMPLETE", label: "Работы завершены", needsComment: true },
];

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
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [filterType, setFilterType] = useState("");
  const [filterStatus, setFilterStatus] = useState("");
  const [filterAddress, setFilterAddress] = useState("");
  const now = useClock();
  const [statusForm, setStatusForm] = useState<{ action: StatusAction; comment: string; numeric: string } | null>(null);
  const [callDialog, setCallDialog] = useState(false);
  const [shortNumber, setShortNumber] = useState("1102");
  const [activeCall, setActiveCall] = useState<OutboundCall | null>(null);
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [serviceMenu, setServiceMenu] = useState<string | null>(null);
  const [working, run] = useAction(setError);
  const audioRef = useRef<HTMLAudioElement | null>(null);

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

  // Softphone audio: ringback → answer («Слушаю вас») → ack («информация принята»).
  useEffect(() => {
    const media = activeCall?.media;
    if (!activeCall || !media) {
      audioRef.current?.pause();
      audioRef.current = null;
      return;
    }
    let url: string | null = null;
    if (activeCall.state === "DIALING" || activeCall.state === "RINGING") url = media.ringbackUrl;
    else if (activeCall.state === "CONNECTED") url = media.answerUrl;
    else if (activeCall.state === "ACKNOWLEDGED") url = media.acknowledgementUrl;
    if (!url) return;
    const audio = new Audio(url);
    audioRef.current?.pause();
    audioRef.current = audio;
    void audio.play().catch(() => undefined);
    return () => {
      audio.pause();
      if (audioRef.current === audio) audioRef.current = null;
    };
  }, [activeCall?.id, activeCall?.state, activeCall?.media?.ringbackUrl, activeCall?.media?.answerUrl, activeCall?.media?.acknowledgementUrl]);

  useEffect(() => {
    if (!selectedId) return;
    const timer = window.setInterval(() => {
      void loadCard(selectedId).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [selectedId, loadCard]);

  const filteredCards = useMemo(() => {
    return cards.filter((item) => {
      const q = search.trim().toLowerCase();
      if (q && ![item.number, item.incidentTypeLabel, item.addressLabel, statusLabels[item.status], item.description]
        .some((value) => value?.toLowerCase().includes(q))) return false;
      if (filterType && !item.incidentTypeLabel.toLowerCase().includes(filterType.trim().toLowerCase())) return false;
      if (filterStatus && item.status !== filterStatus) return false;
      if (filterAddress && !item.addressLabel.toLowerCase().includes(filterAddress.trim().toLowerCase())) return false;
      return true;
    });
  }, [cards, search, filterType, filterStatus, filterAddress]);

  const openCard = (id: string) => {
    setSelectedId(id);
    setStatusForm(null);
    void run(() => loadCard(id));
  };

  const refreshAfter = async (updated: IncidentCard, message: string) => {
    setCard(updated);
    setNotice(message);
    await loadCards();
  };

  const applyStatus = () => {
    if (!card || !statusForm) return;
    const { action, comment } = statusForm;
    const option = STATUS_OPTIONS.find((o) => o.action === action);
    if (option?.needsComment && !comment.trim()) return;
    void run(async () => {
      const updated = action === "ACCEPT" || action === "DECLINE"
        ? await api.accept(token, card.id, action, action === "DECLINE" ? comment : undefined)
        : await api.react(token, card.id, action, option?.needsComment ? comment : undefined);
      await refreshAfter(updated, statusLabels[updated.status] ?? "Действие выполнено");
      setStatusForm(null);
    });
  };

  const openStatusForm = () => {
    if (!card) return;
    const first = STATUS_OPTIONS.find((o) => card.allowedActions.includes(o.action));
    if (!first) {
      setNotice("Нет доступных статусов для текущей карточки");
      return;
    }
    setStatusForm({ action: first.action, comment: "", numeric: "" });
  };

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

  const resetFilters = () => {
    setSearch("");
    setFilterType("");
    setFilterStatus("");
    setFilterAddress("");
  };

  return (
    <main className="arm-shell">
      <TopStrip label={label} onLogout={onLogout} nav={nav} />

      {!card ? (
        <IncidentJournal
          cards={filteredCards}
          search={search}
          setSearch={setSearch}
          advancedOpen={advancedOpen}
          setAdvancedOpen={setAdvancedOpen}
          filterType={filterType}
          setFilterType={setFilterType}
          filterStatus={filterStatus}
          setFilterStatus={setFilterStatus}
          filterAddress={filterAddress}
          setFilterAddress={setFilterAddress}
          onReset={resetFilters}
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
          onBack={() => { setCard(null); setSelectedId(null); setStatusForm(null); }}
          onPencil={openStatusForm}
          onCall={() => setCallDialog(true)}
          onEndCall={endCall}
          working={working}
          workstation={context.workstation.number}
        />
      )}

      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />

      {statusForm && card && (
        <div className="status-form" role="dialog" aria-label="Проставление статуса реагирования">
          <label className="status-form-select">
            <span>Статус</span>
            <select
              value={statusForm.action}
              onChange={(e) => setStatusForm({ ...statusForm, action: e.target.value as StatusAction })}
            >
              {STATUS_OPTIONS.filter((o) => card.allowedActions.includes(o.action)).map((o) => (
                <option key={o.action} value={o.action}>{o.label}</option>
              ))}
            </select>
          </label>
          <input
            className="status-form-numeric"
            inputMode="numeric"
            value={statusForm.numeric}
            onChange={(e) => setStatusForm({ ...statusForm, numeric: e.target.value.replace(/\D/g, "").slice(0, 4) })}
            placeholder="№"
            title="Номер / количество (учебное поле)"
          />
          <input
            autoFocus
            className="status-form-comment"
            value={statusForm.comment}
            onChange={(e) => setStatusForm({ ...statusForm, comment: e.target.value })}
            placeholder="Комментарий — основание и результат действий"
          />
          <button
            className="ok"
            title="Сохранить статус"
            disabled={working || (STATUS_OPTIONS.find((o) => o.action === statusForm.action)?.needsComment && !statusForm.comment.trim())}
            onClick={applyStatus}
          >✓</button>
          <button className="cancel" title="Отмена" onClick={() => setStatusForm(null)}>✕</button>
        </div>
      )}

      {callDialog && card && (
        <Modal title="Исходящий вызов" onClose={() => setCallDialog(false)}>
          <p className="dialog-copy">Выберите должностное лицо или введите короткий номер. После соединения прозвучит ответ абонента.</p>
          <div className="target-list">
            {card.callTargets.map((target) => (
              <button key={target.id} className={shortNumber === target.shortNumber ? "selected" : ""} onClick={() => setShortNumber(target.shortNumber)}>
                <b>{target.shortNumber}</b><span>{target.displayName}<small>{target.organization} · {target.voice === "FEMALE" ? "жен." : "муж."}</small></span>
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

function IncidentJournal({ cards, search, setSearch, advancedOpen, setAdvancedOpen, filterType, setFilterType, filterStatus, setFilterStatus, filterAddress, setFilterAddress, onReset, onOpen, now, context, loading, onSubmit, session }: {
  cards: CardListItem[];
  search: string;
  setSearch: (value: string) => void;
  advancedOpen: boolean;
  setAdvancedOpen: (value: boolean) => void;
  filterType: string;
  setFilterType: (value: string) => void;
  filterStatus: string;
  setFilterStatus: (value: string) => void;
  filterAddress: string;
  setFilterAddress: (value: string) => void;
  onReset: () => void;
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
            <button type="button" className="linkish" onClick={() => setAdvancedOpen(!advancedOpen)}>
              расширенный по параметрам{advancedOpen ? "⌃" : "⌄"}
            </button>
            <button onClick={onReset}>сбросить</button>
          </div>
          {advancedOpen && (
            <div className="advanced-search">
              <label><span>Тип</span><input value={filterType} onChange={(e) => setFilterType(e.target.value)} placeholder="пожар, ДТП…" /></label>
              <label><span>Статус</span>
                <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}>
                  <option value="">все</option>
                  {Object.entries(statusLabels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </label>
              <label><span>Адрес</span><input value={filterAddress} onChange={(e) => setFilterAddress(e.target.value)} placeholder="улица, округ…" /></label>
            </div>
          )}
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
          const flags = cardFlags(item.description);
          return <button key={item.id} className={`incident-row ${item.sla.acceptanceOverdue || item.sla.processingOverdue ? "overdue" : ""}`} onClick={() => onOpen(item.id)}>
            <span>⌄　◆　⚡</span><span>0</span><span>0</span><span>{context.workstation.number}</span><b>{displayCardNumber(item.number)}</b>
            <span>{new Date(item.receivedAt).toLocaleDateString("ru-RU")}</span><strong>{timeOnly(item.receivedAt)}</strong>
            <b title={item.incidentTypeLabel}>{item.incidentTypeLabel}</b>
            <span>{flags.victims ? "Да" : "Нет"}</span>
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

function CardWorkspace({ card, now, activeCall, serviceMenu, setServiceMenu, onBack, onPencil, onCall, onEndCall, working, workstation }: {
  card: IncidentCard;
  now: number;
  activeCall: OutboundCall | null;
  serviceMenu: string | null;
  setServiceMenu: (value: string | null) => void;
  onBack: () => void;
  onPencil: () => void;
  onCall: () => void;
  onEndCall: () => void;
  working: boolean;
  workstation: string;
}) {
  const callActive = activeCall && !["ENDED", "FAILED", "NO_ANSWER", "CANCELLED"].includes(activeCall.state);
  const awaitingAcceptance = card.status === "RECEIVED" || card.status === "RECEIVED_BY_SERVICE";
  const opening = card.status === "RECEIVED" && card.opening.readyAt && !card.opening.openedAt;
  const openingLeftMs = opening ? Math.max(0, new Date(card.opening.readyAt!).getTime() - now) : 0;
  const flags = cardFlags(card.description, card.features);
  const ownHistory = card.timeline.filter((e) => e.actorRole !== "TEACHER");
  return (
    <section className="card-workspace">
      <div className="phone-strip">
        <button className="back-button" onClick={onBack}>‹</button>
        <div className="phone-state"><b>☎</b><span>{activeCall ? callLabels[activeCall.state] : "Отключение"}<small>{activeCall?.target.displayName ?? "записи звонков　 список SMS"}</small></span></div>
        <div className="phone-slot"><b>☎</b><span>{activeCall?.target.shortNumber ?? "АОН"}</span></div>
        <div className="phone-slot"><b>☎</b><span>{activeCall?.target.organization ?? "предоставленный"}</span></div>
        <div className="phone-slot"><b>☎</b><span>телефон на место</span></div>
        <div className="card-number"><b>Происшествие {displayCardNumber(card.number)}</b><small>созд. {dateTime(card.receivedAt)}<br />Опер., АРМ {workstation}</small></div>
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

      {opening ? (
        <div className="card-opening" role="status" aria-live="polite">
          <span className="card-opening-spinner" aria-hidden="true" />
          <b>Открытие карточки происшествия</b>
          <span>Получение данных из системы 112…</span>
          <strong>{(openingLeftMs / 1000).toFixed(1)} сек.</strong>
        </div>
      ) : (
        <>
          <div className="card-summary-row">
            <span>ФИО заявителя: <b>{card.caller.fullName ?? "не указано"}</b></span>
            <span>Пострадавшие: {flagLabel(flags.victims)}　 Отказ от скорой: {flagLabel(flags.ambulanceRefused)}　 Заблокированные: {flagLabel(flags.blocked)}</span>
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
        </>
      )}

      <div className="service-dock">
        <button className="services-label">Службы:</button>
        <div className="service-tiles">
          {card.assignedServices.map((service) => {
            const own = service.code === card.ownServiceCode;
            const expanded = serviceMenu === service.id;
            const progress = card.serviceProgress.find((item) => item.service.code === service.code);
            return (
              <div key={service.id} className={`service-tile ${expanded ? "active" : ""} ${own ? "" : "foreign"} service-${progress?.status.toLowerCase() ?? "received"}`}>
                <button
                  className="tile-body"
                  title={own ? undefined : "Статус другой службы — только просмотр"}
                  onClick={() => { if (!own) return; setServiceMenu(expanded ? null : service.id); }}
                >
                  <i className="tile-chevron">{expanded ? "⌄" : "⌃"}</i>
                  <b>{service.label}</b>
                  <span>{progress ? `${timeOnly(progress.statusChangedAt)} ${statusLabels[progress.status] ?? progress.status}` : `${timeOnly(card.receivedAt)} Добавлена`}</span>
                </button>
                {expanded && own && (
                  <button className="tile-pencil" title="Проставить статус реагирования" onClick={onPencil} disabled={working || card.allowedActions.length === 0}>✎</button>
                )}
                {expanded && own && (
                  <div className="tile-history" aria-label="История статусов службы">
                    {ownHistory.map((event) => (
                      <div key={event.id}>
                        <time>{timeOnly(event.occurredAt)}</time>
                        <b>{actionLabels[event.action] ?? event.action}</b>
                        {event.comment && <span>{event.comment}</span>}
                      </div>
                    ))}
                    {["ACCEPTED", "RESPONSE_STARTED", "ARRIVED", "WORK_IN_PROGRESS"].includes(card.status) && (
                      callActive
                        ? <button type="button" className="tile-call hangup" onClick={onEndCall} disabled={working}>☎ Завершить звонок</button>
                        : <button type="button" className="tile-call" onClick={onCall} disabled={working}>☎ Исходящий звонок</button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
        <button className="dock-icon" title="Сообщения">!</button><button className="dock-icon" onClick={onBack}>×</button>
      </div>
    </section>
  );
}
