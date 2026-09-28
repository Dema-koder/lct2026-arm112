"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, type Assessment, type CardDraft, type CardDraftPatch, type FormalAddress, type JournalRow, type ServiceItem, type SurveyTree, type TopTypeItem, type TraineeContext } from "../../../lib/api";
import { countdown, dateTime, displayCardNumber, elapsed } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, TopStrip, useAction, useClock, useNotice, useRingTone, useSocket } from "../common";
import { AssessmentView } from "../trainee/Results";
import { IncidentJournal, type JournalRowView } from "../journal/IncidentJournal";

type Props = {
  token: string;
  context: TraineeContext;
  onLogout: () => void;
  onReload: () => Promise<void>;
  nav: React.ReactNode;
  label: string;
};

const ADDRESS_FIELDS: Array<[keyof FormalAddress, string, string]> = [
  ["country", "Страна", "af-2"],
  ["region", "Субъект", "af-5"],
  ["locality", "Населённый пункт", "af-5"],
  ["object", "Объект", "af-4"],
  ["okrug", "Округ", "af-3"],
  ["district", "Район", "af-5"],
  ["street", "Улица", "af-6"],
  ["house", "Дом/Вл.", "af-2"],
  ["building", "Корпус", "af-2"],
  ["structure", "Стр/соор.", "af-2"],
  ["apartment", "Квартира/офис", "af-3"],
  ["entrance", "Подъезд", "af-3"],
  ["floor", "Этаж", "af-3"],
  ["code", "Код", "af-3"],
];

/**
 * Экран оператора 112: главный экран с журналом смены и входящими вызовами (dds-02.png), по принятому
 * вызову — карточка (card-01.png … card-09.png). Единственная кнопка действия — «сохранить»; поля автосохраняются.
 */
export function FillWorkspace({ token, context, onLogout, onReload, nav, label }: Props) {
  const session = context.activeSession!;
  const [draft, setDraft] = useState<CardDraft | null>(null);
  const [journal, setJournal] = useState<JournalRow[]>([]);
  const [search, setSearch] = useState("");
  const [viewRow, setViewRow] = useState<JournalRow | null>(null);
  const [askedAddress, setAskedAddress] = useState(false);
  const ringing = !draft && session.state === "ACTIVE" && !!session.incomingCall;
  const ring = useRingTone(ringing);
  const [types, setTypes] = useState<TopTypeItem[]>([]);
  const [typeQuery, setTypeQuery] = useState("");
  const [survey, setSurvey] = useState<SurveyTree | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [assessment, setAssessment] = useState<Assessment | null>(null);
  const [done, setDone] = useState(false);
  const [showCaller, setShowCaller] = useState(true);
  const [addServices, setAddServices] = useState(false);
  const [working, run] = useAction(setError);
  const now = useClock();
  const pending = useRef<CardDraftPatch>({});
  const timer = useRef<number | undefined>(undefined);

  const loadJournal = useCallback(async () => {
    try {
      setJournal((await api.journal(token, session.id)).rows);
    } catch { /* журнал обновится по следующему событию */ }
  }, [token, session.id]);

  /** Принять входящий вызов — по нему создаётся карточка. */
  const answerCall = useCallback(async () => {
    try {
      const next = await api.createDraft(token, session.id);
      setDraft(next);
      setAskedAddress(false);
      setError("");
    } catch (err) {
      if (err instanceof Error && err.message.includes("отработаны")) setDone(true);
      else setError(err instanceof Error ? err.message : "Не удалось принять вызов");
      await onReload();
    }
  }, [token, session.id, onReload]);

  useEffect(() => {
    // после перезагрузки страницы открытый черновик возвращается на экран
    void Promise.resolve().then(async () => {
      try {
        const open = (await api.drafts(token, session.id)).find((d) => d.state === "DRAFT");
        if (open) setDraft(open);
      } catch { /* нет черновика — главный экран */ }
      await loadJournal();
    });
    api.cardTypes(token).then(setTypes).catch(() => undefined);
  }, [loadJournal, token, session.id]);

  // опросная карта — у типа верхнего уровня, как в ПОВ-112
  useEffect(() => {
    const top = draft?.topTypeId;
    if (!top) { queueMicrotask(() => setSurvey(null)); return; }
    api.surveyTree(token, top).then(setSurvey).catch(() => setSurvey(null));
  }, [draft?.topTypeId, token]);

  useSocket(token, async (event) => {
    if (event.type === "training.session_state_changed" || event.type === "training.session_started") { await onReload(); return; }
    if (event.type.startsWith("call.")) {
      if (event.type === "call.missed") setNotice("Вызов пропущен — заявитель перезвонит");
      if (event.type === "call.lost") setNotice("Заявитель не дозвонился — вызов потерян");
      await onReload();
    }
    if (event.type === "draft.saved") await loadJournal();
  });

  /** Автосохранение: правки копятся и уходят одним PATCH через 500 мс. */
  const queue = useCallback((patch: CardDraftPatch) => {
    if (!draft) return;
    setDraft({ ...draft, ...(patch as Partial<CardDraft>) } as CardDraft);
    pending.current = { ...pending.current, ...patch };
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(async () => {
      const body = pending.current;
      pending.current = {};
      try {
        setDraft(await api.patchDraft(token, draft.id, body));
        setError("");
      } catch (err) {
        setError(err instanceof Error ? err.message : "Не удалось сохранить поля");
      }
    }, 500);
  }, [draft, token]);

  const flush = async () => {
    if (!draft) return;
    if (timer.current) window.clearTimeout(timer.current);
    const body = pending.current;
    pending.current = {};
    if (Object.keys(body).length > 0) setDraft(await api.patchDraft(token, draft.id, body));
  };

  const save = () => draft && run(async () => {
    await flush();
    const saved = await api.saveDraft(token, draft.id);
    setNotice(`Карточка ${displayCardNumber(saved.number)} сохранена`);
    const next = await api.context(token);
    if (!next.activeSession || next.activeSession.state !== "ACTIVE") {
      // последняя вводная — занятие завершилось само, показываем результат по правилу вида
      setDone(true);
      const results = await api.results(token);
      const mine = results.find((r) => r.sessionId === session.id);
      if (mine?.visible && mine.assessmentId) setAssessment(await api.assessment(token, mine.assessmentId));
      await onReload();
      return;
    }
    // обратно на главный экран: сохранённая карточка появляется в журнале, следующий вызов — по очереди
    setDraft(null);
    setSurvey(null);
    setTypeQuery("");
    await loadJournal();
    await onReload();
  });

  const filteredTypes = useMemo(() => {
    const q = typeQuery.trim().toLowerCase().replace("ё", "е");
    if (!q) return [];
    return types.filter((t) => t.label.toLowerCase().replace("ё", "е").includes(q)).slice(0, 10);
  }, [types, typeQuery]);

  /** Выбор типа верхнего уровня: открывает опросную карту, ответы обнуляются. */
  const chooseType = (id: string) => {
    if (!draft) return;
    queue({ topTypeId: id, surveyAnswers: [] });
    setTypeQuery("");
  };

  const answer = (questionId: string, optionId: string | null, text: string | null) => {
    if (!draft) return;
    const rest = draft.surveyAnswers.filter((a) => a.questionId !== questionId);
    queue({ surveyAnswers: [...rest, { questionId, optionId, text }] });
  };

  /** Вопрос показывается, если выполнено хотя бы одно условие showWhen (пусто — всегда). */
  const visibleQuestion = (question: SurveyTree["questions"][number]) => {
    if (!draft || question.showWhen.length === 0) return true;
    return question.showWhen.some((cond) =>
      Object.entries(cond).every(([qid, allowed]) =>
        draft.surveyAnswers.some((a) => a.questionId === qid && a.optionId !== null && allowed.includes(a.optionId))));
  };

  const hint = (field: string) => draft?.hints.find((h) => h.field === field)?.message;
  const number = draft ? displayCardNumber(draft.number) : "—";
  const overdue = draft?.deadlineAt ? new Date(draft.deadlineAt).getTime() < now : false;

  if (done && !draft) {
    return (
      <main className="arm-shell">
        <TopStrip label={label} onLogout={onLogout} nav={nav} />
        <section className="waiting-screen">
          <b>Все вводные занятия отработаны</b>
          <span>Результат — в разделе «Мои результаты».</span>
        </section>
        {assessment && (
          <Modal title="Результат занятия" onClose={() => setAssessment(null)} wide>
            <AssessmentView assessment={assessment} token={token} />
            <div className="dialog-actions"><button className="primary" onClick={() => setAssessment(null)}>Закрыть</button></div>
          </Modal>
        )}
        <Notice text={notice} />
      </main>
    );
  }

  const call = session.incomingCall;
  const journalRows: JournalRowView[] = journal
    .filter((r) => { const q = search.trim().toLowerCase(); return !q || [r.number, r.incidentTypeLabel, r.addressLabel ?? "", r.description].some((v) => v.toLowerCase().includes(q)); })
    .map((r) => ({
      id: r.id, number: r.number, receivedAt: r.receivedAt, workstationNumber: r.workstationNumber ?? "—",
      incidentTypeLabel: r.incidentTypeLabel || "Происшествие", addressLabel: r.addressLabel ?? "", description: r.description,
      senderLabel: "Оператор 112", statusLabel: r.status, foreign: r.kind === "BACKGROUND",
    }));

  return (
    <main className={`arm-shell ${draft ? "fill-shell" : ""}`}>
      <TopStrip label={label} onLogout={onLogout} nav={nav} />
      {!draft ? (
        <>
          {/* полоса телефона: входящий вызов принимает оператор, карточка создаётся по вызову */}
          <div className={`phone-bar ${call ? "ringing" : ""}`}>
            <b>☎</b>
            {call ? (
              <>
                <span className="phone-bar-title">Входящий вызов{call.missedCount > 0 ? " · повторный" : ""}</span>
                <span className="phone-bar-number">{call.phone ?? "номер не определён"}</span>
                <span className="phone-bar-name">{call.callerName ?? ""}</span>
                <button type="button" className="answer-button" disabled={working} onClick={() => void run(answerCall)}>принять</button>
              </>
            ) : (
              <>
                <span className="phone-bar-title">Отключение</span>
                <span className="phone-bar-name">записи звонков　 список SMS</span>
                {session.pendingScenarios + session.queuedCalls === 0 ? <span className="phone-bar-hint">вызовов больше не ожидается</span> : <span className="phone-bar-hint">ожидание вызова…</span>}
              </>
            )}
            {session.queuedCalls > 0 && <span className="phone-bar-queue">в очереди: {session.queuedCalls}</span>}
            <button type="button" className="ring-toggle" title={ring.enabled ? "Выключить звонок" : "Включить звонок"} onClick={ring.toggle}>{ring.enabled ? "🔔" : "🔕"}</button>
          </div>
          <IncidentJournal
            rows={journalRows}
            search={search}
            setSearch={setSearch}
            onOpen={(id) => setViewRow(journal.find((r) => r.id === id) ?? null)}
            now={now}
            workstationLabel={context.workstation.label}
            loading={false}
          />
          {viewRow && (
            <Modal title={`Происшествие ${viewRow.number.replace(/\D/g, "").slice(-8)}`} onClose={() => setViewRow(null)}>
              <div className="journal-card-view">
                <p><em>Создана:</em> {dateTime(viewRow.receivedAt)} · Опер., АРМ {viewRow.workstationNumber ?? "—"} · {viewRow.status}</p>
                <p><em>Тип:</em> {viewRow.incidentTypeLabel || "—"}</p>
                <p><em>Адрес:</em> {viewRow.addressLabel || "—"}</p>
                <p><em>Заявитель:</em> {viewRow.callerName ?? "не указан"}</p>
                <p><em>Описание:</em> {viewRow.description}</p>
                <p><em>Службы:</em> {viewRow.services.join(", ") || "—"}</p>
              </div>
              <div className="dialog-actions"><button className="primary" onClick={() => setViewRow(null)}>Закрыть</button></div>
            </Modal>
          )}
        </>
      ) : (
        <section className="fill-card">
          {/* верхняя полоса: телефоны, номер карточки, таймер */}
          <div className="fill-top">
            <div className="fill-phones">
              <div className="phone-box">
                <span>АОН</span>
                <input value={draft.phones.ani ?? ""} onChange={(e) => queue({ phones: { ...draft.phones, ani: e.target.value } })} placeholder="+7 (___) ___-__-__" />
                <button type="button" title="Вводная заявителя" className={showCaller ? "active" : ""} onClick={() => setShowCaller(!showCaller)}>☎</button>
              </div>
              <div className="phone-box">
                <span>предоставленный</span>
                <input value={draft.phones.provided ?? ""} onChange={(e) => queue({ phones: { ...draft.phones, provided: e.target.value } })} />
                <button type="button" onClick={() => queue({ phones: { ...draft.phones, provided: draft.phones.ani } })}>АОН</button>
              </div>
              <div className="phone-box">
                <span>телефон на место</span>
                <input value={draft.phones.onSite ?? ""} onChange={(e) => queue({ phones: { ...draft.phones, onSite: e.target.value } })} />
                <button type="button" onClick={() => queue({ phones: { ...draft.phones, onSite: draft.phones.ani } })}>АОН</button>
              </div>
            </div>
            <div className="fill-number">
              <b>Происшествие {number}</b>
              <small>начато {dateTime(draft.startedAt)}<br />Опер., АРМ {context.workstation.number}{session.queuedCalls > 0 ? <span title="Вызовы ждут в очереди — заявители дозвонятся, когда вы сохраните карточку"> · в очереди вызовов: {session.queuedCalls}</span> : ""}</small>
            </div>
            <div className={`card-timer ${overdue ? "overdue" : ""}`} title={`отработать за ${countdown(draft.deadlineAt, now)}`}>
              <b>{elapsed(draft.startedAt, now)}</b>
              <small>{countdown(draft.deadlineAt, now)}</small>
            </div>
          </div>

          {showCaller && (
            <div className="caller-bubble">
              <b>Заявитель сообщает:</b>
              <p>{draft.callerText}</p>
              {askedAddress && (
                <p className="caller-answer"><b>Адрес со слов заявителя:</b> {draft.callerAddress || "заявитель адрес назвать не может — уточните ориентиры и запишите в описательный адрес"}</p>
              )}
              <small>{draft.caller.fullName ?? "имя не названо"}{draft.phones.ani ? ` · ${draft.phones.ani}` : ""}</small>
              {!askedAddress && <button type="button" className="ask-button" onClick={() => setAskedAddress(true)}>уточнить адрес у заявителя</button>}
            </div>
          )}

          <div className="fill-columns">
            {/* левая колонка: заявитель и адрес */}
            <div className="fill-left">
              <fieldset className="fill-block">
                <legend>Заявитель</legend>
                <div className="fill-row">
                  <label className="w-l"><span>Фамилия и имя</span>
                    <input value={draft.caller.fullName ?? ""} onChange={(e) => queue({ caller: { ...draft.caller, fullName: e.target.value } })} /></label>
                  <label className="w-m"><span>Статус заявителя</span>
                    <select value={draft.caller.status ?? ""} onChange={(e) => queue({ caller: { ...draft.caller, status: e.target.value } })}>
                      {["заявитель", "пострадавший", "очевидец", "родственник", "должностное лицо"].map((s) => <option key={s} value={s}>{s}</option>)}
                    </select></label>
                </div>
              </fieldset>

              <fieldset className={`fill-block ${hint("address.street") || hint("address.house") ? "hinted" : ""}`}>
                <legend>Адрес</legend>
                <div className="address-fields">
                  {ADDRESS_FIELDS.map(([key, title, width]) => (
                    <label key={key} className={`${width} ${hint(`address.${key}`) ? "hinted" : ""}`} title={hint(`address.${key}`)}>
                      <span>{title}</span>
                      <input value={(draft.address[key] as string | null) ?? ""} onChange={(e) => queue({ address: { ...draft.address, [key]: e.target.value } })} />
                    </label>
                  ))}
                </div>
                <label className="w-full"><span>Описательный адрес</span>
                  <input value={draft.address.descriptive ?? ""} onChange={(e) => queue({ address: { ...draft.address, descriptive: e.target.value } })} placeholder="ориентиры со слов заявителя" /></label>
                {(hint("address.street") || hint("address.house")) && (
                  <p className="hint-line">💡 {hint("address.street") ?? hint("address.house")}</p>
                )}
                <div className="fill-row">
                  <button type="button" className="ghost" onClick={() => queue({ address: { country: "Россия" } })}>очистить адрес</button>
                </div>
              </fieldset>

              <fieldset className="fill-block">
                <legend>Описание со слов заявителя</legend>
                <textarea
                  maxLength={1999}
                  value={draft.description}
                  onChange={(e) => queue({ description: e.target.value })}
                  placeholder="Что случилось, кто пострадал, что видит заявитель"
                />
                <div className="counter">{draft.description.length} / 1999</div>
              </fieldset>
            </div>

            {/* правая колонка: признаки, «что случилось», опросная карта */}
            <div className="fill-right">
              <div className="flags-bar">
                <button type="button" className={draft.flags.victims ? "on" : ""} onClick={() => queue({ flags: { ...draft.flags, victims: !draft.flags.victims, victimsCount: draft.flags.victims ? null : (draft.flags.victimsCount ?? 1) } })}>Пострадавшие{draft.flags.victims ? `: ${draft.flags.victimsCount ?? 1}` : ""}</button>
                {draft.flags.victims && (
                  <input className="w-xs" type="number" min={1} value={draft.flags.victimsCount ?? 1} onChange={(e) => queue({ flags: { ...draft.flags, victimsCount: Number(e.target.value) || 1 } })} />
                )}
                <button type="button" className={draft.flags.ambulanceRefused ? "on" : ""} onClick={() => queue({ flags: { ...draft.flags, ambulanceRefused: !draft.flags.ambulanceRefused } })}>Нет на месте/Отказ от скорой</button>
                <button type="button" className={draft.flags.blocked ? "on" : ""} onClick={() => queue({ flags: { ...draft.flags, blocked: !draft.flags.blocked } })}>Нет доступа/Заблокированные</button>
                <button type="button" className={`red ${draft.flags.noContact ? "on" : ""}`} onClick={() => queue({ flags: { ...draft.flags, noContact: !draft.flags.noContact } })}>нет контакта</button>
                <button type="button" className={`red ${draft.flags.callDropped ? "on" : ""}`} onClick={() => queue({ flags: { ...draft.flags, callDropped: !draft.flags.callDropped } })}>срыв звонка</button>
              </div>

              <fieldset className={`fill-block ${hint("incidentTypeIds") ? "hinted" : ""}`}>
                <legend>Что случилось?</legend>
                <div className="type-search">
                  <input value={typeQuery} onChange={(e) => setTypeQuery(e.target.value)} placeholder="введите тип происшествия" />
                  {filteredTypes.length > 0 && (
                    <ul className="type-dropdown">
                      {filteredTypes.map((t) => <li key={t.id}><button type="button" onClick={() => chooseType(t.id)}>{t.label}</button></li>)}
                    </ul>
                  )}
                </div>
                {draft.topTypeId && (
                  <div className="type-chips">
                    <button type="button" className="chip selected" onClick={() => queue({ topTypeId: "", surveyAnswers: [] })}>
                      {types.find((t) => t.id === draft.topTypeId)?.label ?? draft.topTypeId} ×
                    </button>
                  </div>
                )}
                <div className="type-chips">
                  {types.filter((t) => t.frequent && t.id !== draft.topTypeId).map((t) => (
                    <button type="button" key={t.id} className="chip" onClick={() => chooseType(t.id)}>{t.label}</button>
                  ))}
                </div>
                <small className="chips-title">Значимые типы происшествий</small>
                {hint("incidentTypeIds") && <p className="hint-line">💡 {hint("incidentTypeIds")}</p>}
              </fieldset>

              {survey && survey.questions.length > 0 && (
                <fieldset className="fill-block survey">
                  <legend>{survey.label}</legend>
                  {survey.questions.filter(visibleQuestion).map((q) => {
                    const current = draft.surveyAnswers.find((a) => a.questionId === q.id);
                    return (
                      <div key={q.id} className="survey-row">
                        <span title={q.synthetic ? "Ветка достроена по смыслу: скриншота этой карты заказчик не присылал" : undefined}>
                          {q.text}{q.synthetic ? " *" : ""}
                        </span>
                        {q.kind === "CHOICE" ? (
                          <div className="type-chips">
                            {q.options.map((o) => (
                              <button type="button" key={o.id} className={`chip ${current?.optionId === o.id ? "selected" : ""}`} onClick={() => answer(q.id, o.id, null)}>{o.label}</button>
                            ))}
                          </div>
                        ) : (
                          <input value={current?.text ?? ""} onChange={(e) => answer(q.id, null, e.target.value)} />
                        )}
                      </div>
                    );
                  })}
                  <p className="class-line">Класс.: {draft.incidentTypeIds.join("; ") || "определится по ответам"};</p>
                </fieldset>
              )}
            </div>
          </div>

          {/* нижняя оранжевая панель служб */}
          <div className="service-dock orange">
            <button className="services-label" type="button">Службы:</button>
            <div className="service-tiles">
              {draft.services.length === 0 && <span className="dock-empty">подберутся по типу происшествия и адресу</span>}
              {draft.services.map((s) => (
                <div key={s.code} className={`service-tile ${s.auto ? "auto" : "manual"}`} title={s.auto ? "Подобрана по классификатору" : "Добавлена вручную"}>
                  <div className="tile-body"><b>{s.label}</b><span>{s.auto ? "по ЕКП" : "вручную"}</span></div>
                </div>
              ))}
              <button type="button" className="dock-plus" title="Добавить службу" onClick={() => setAddServices(true)}>+</button>
            </div>
            <button type="button" className="save-button" disabled={working} onClick={save}>сохранить</button>
          </div>
        </section>
      )}

      {addServices && draft && (
        <AddServicesDialog token={token} draft={draft} onClose={() => setAddServices(false)} onAdd={(codes) => { queue({ extraServiceCodes: codes }); setAddServices(false); }} />
      )}

      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </main>
  );
}

/** Окно «+»: автоподобранные синим, остальные серым; клик по серой добавляет. */
function AddServicesDialog({ token, draft, onClose, onAdd }: { token: string; draft: CardDraft; onClose: () => void; onAdd: (codes: string[]) => void }) {
  const [all, setAll] = useState<ServiceItem[]>([]);
  const [chosen, setChosen] = useState<string[]>(draft.services.filter((s) => !s.auto).map((s) => s.code));
  const [query, setQuery] = useState("");
  useEffect(() => {
    // полный справочник ПОВ-112: службы города, ведомства, ДДС районов, округов и поселений
    api.serviceCatalog(token).then(setAll).catch(() => undefined);
  }, [token]);
  const auto = new Set(draft.services.filter((s) => s.auto).map((s) => s.code));
  return (
    <Modal title="Службы на вызов" onClose={onClose}>
      <input className="status-form-comment w-full" placeholder="поиск службы" value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="service-picker">
        {all.filter((s) => !query || [s.label, s.fullName].some((v) => v.toLowerCase().includes(query.toLowerCase()))).slice(0, 120).map((s) => {
          const isAuto = auto.has(s.code);
          const isChosen = chosen.includes(s.code);
          return (
            <button type="button" key={s.code} className={`service-option ${isAuto || isChosen ? "selected" : ""}`} disabled={isAuto}
              title={isAuto ? "Подобрана автоматически — удалить нельзя" : s.fullName}
              onClick={() => setChosen(isChosen ? chosen.filter((c) => c !== s.code) : [...chosen, s.code])}>{s.fullName}</button>
          );
        })}
      </div>
      <div className="dialog-actions">
        <button className="secondary" onClick={onClose}>Отмена</button>
        <button className="primary" onClick={() => onAdd(chosen)}>Сохранить и закрыть</button>
      </div>
    </Modal>
  );
}
