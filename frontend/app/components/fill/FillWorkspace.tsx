"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, type Assessment, type CardDraft, type CardDraftPatch, type FormalAddress, type IncidentTypeItem, type SurveyCard, type TraineeContext } from "../../../lib/api";
import { countdown, dateTime, displayCardNumber, elapsed } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, TopStrip, useAction, useClock, useNotice, useSocket } from "../common";
import { AssessmentView } from "../trainee/Results";

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
 * Экран оператора 112 — режим заполнения карточки (card-01.png … card-09.png).
 * Единственная кнопка действия — «сохранить»; поля автосохраняются.
 */
export function FillWorkspace({ token, context, onLogout, onReload, nav, label }: Props) {
  const session = context.activeSession!;
  const [draft, setDraft] = useState<CardDraft | null>(null);
  const [types, setTypes] = useState<IncidentTypeItem[]>([]);
  const [typeQuery, setTypeQuery] = useState("");
  const [survey, setSurvey] = useState<SurveyCard | null>(null);
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

  const loadDraft = useCallback(async () => {
    try {
      const next = await api.createDraft(token, session.id);
      setDraft(next);
      setError("");
    } catch (err) {
      if (err instanceof Error && err.message.includes("отработаны")) setDone(true);
      else setError(err instanceof Error ? err.message : "Не удалось получить вводную");
    }
  }, [token, session.id]);

  useEffect(() => {
    void Promise.resolve().then(loadDraft);
    api.incidentTypes(token).then(setTypes).catch(() => undefined);
  }, [loadDraft, token]);

  useEffect(() => {
    const first = draft?.incidentTypeIds[0];
    if (!first) { queueMicrotask(() => setSurvey(null)); return; }
    api.surveyCard(token, first).then(setSurvey).catch(() => setSurvey(null));
  }, [draft?.incidentTypeIds, token]);

  useSocket(token, async (event) => {
    if (event.type === "training.session_state_changed" || event.type === "training.session_started") await onReload();
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
    setDraft(null);
    setSurvey(null);
    setTypeQuery("");
    await loadDraft();
  });

  const filteredTypes = useMemo(() => {
    const q = typeQuery.trim().toLowerCase().replace("ё", "е");
    if (!q) return [];
    return types.filter((t) => t.label.toLowerCase().replace("ё", "е").includes(q) || t.id.includes(q)).slice(0, 8);
  }, [types, typeQuery]);

  const toggleType = (id: string) => {
    if (!draft) return;
    const next = draft.incidentTypeIds.includes(id)
      ? draft.incidentTypeIds.filter((t) => t !== id)
      : [...draft.incidentTypeIds, id];
    queue({ incidentTypeIds: next });
    setTypeQuery("");
  };

  const answer = (questionId: string, optionId: string | null, text: string | null) => {
    if (!draft) return;
    const rest = draft.surveyAnswers.filter((a) => a.questionId !== questionId);
    queue({ surveyAnswers: [...rest, { questionId, optionId, text }] });
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
            <AssessmentView assessment={assessment} />
            <div className="dialog-actions"><button className="primary" onClick={() => setAssessment(null)}>Закрыть</button></div>
          </Modal>
        )}
        <Notice text={notice} />
      </main>
    );
  }

  return (
    <main className="arm-shell fill-shell">
      <TopStrip label={label} onLogout={onLogout} nav={nav} />
      {!draft ? <div className="boot-screen">Получение вводной…</div> : (
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
              <small>начато {dateTime(draft.startedAt)}<br />Опер., АРМ {context.workstation.number}{session.pendingScenarios > 0 ? <span title="Учебное упрощение: вводные выдаются по одной, следующая — после сохранения. В боевом АРМ оператор видит журнал и создаёт карточку по принятому вызову."> · в очереди ещё {session.pendingScenarios}</span> : ""}</small>
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
              <small>{draft.caller.fullName ?? "имя не названо"}{draft.phones.ani ? ` · ${draft.phones.ani}` : ""}</small>
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
                  <input value={typeQuery} onChange={(e) => setTypeQuery(e.target.value)} placeholder="поиск по названию или части слова" />
                  {filteredTypes.length > 0 && (
                    <ul className="type-dropdown">
                      {filteredTypes.map((t) => <li key={t.id}><button type="button" onClick={() => toggleType(t.id)}>{t.label}</button></li>)}
                    </ul>
                  )}
                </div>
                <div className="type-chips">
                  {draft.incidentTypeIds.map((id) => {
                    const t = types.find((x) => x.id === id);
                    return <button type="button" key={id} className="chip selected" onClick={() => toggleType(id)}>{t?.label ?? id} ×</button>;
                  })}
                </div>
                <small className="chips-title">Часто используемые</small>
                <div className="type-chips">
                  {types.filter((t) => t.frequent && !draft.incidentTypeIds.includes(t.id)).map((t) => (
                    <button type="button" key={t.id} className="chip" onClick={() => toggleType(t.id)}>{t.label}</button>
                  ))}
                </div>
                <small className="chips-title">Значимые типы происшествий</small>
                <div className="type-chips">
                  {types.filter((t) => t.significant && !draft.incidentTypeIds.includes(t.id)).map((t) => (
                    <button type="button" key={t.id} className="chip" onClick={() => toggleType(t.id)}>{t.label}</button>
                  ))}
                </div>
                {hint("incidentTypeIds") && <p className="hint-line">💡 {hint("incidentTypeIds")}</p>}
              </fieldset>

              {survey && survey.questions.length > 0 && (
                <fieldset className="fill-block survey">
                  <legend>Опросная карта: {types.find((t) => t.id === survey.incidentTypeId)?.label ?? survey.incidentTypeId}</legend>
                  {survey.questions.map((q) => {
                    const current = draft.surveyAnswers.find((a) => a.questionId === q.id);
                    return (
                      <div key={q.id} className="survey-row">
                        <span>{q.text}</span>
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
                  <p className="class-line">Класс.: {draft.incidentTypeIds.map((id) => types.find((t) => t.id === id)?.label.toLowerCase() ?? id).join("; ")};</p>
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
  const [all, setAll] = useState<Array<{ code: string; label: string }>>([]);
  const [chosen, setChosen] = useState<string[]>(draft.services.filter((s) => !s.auto).map((s) => s.code));
  const [query, setQuery] = useState("");
  useEffect(() => {
    api.references(token).then((r) => setAll(r.services.map((s) => ({ code: s.code, label: s.label })))).catch(() => undefined);
  }, [token]);
  const auto = new Set(draft.services.filter((s) => s.auto).map((s) => s.code));
  return (
    <Modal title="Службы на вызов" onClose={onClose}>
      <input className="status-form-comment w-full" placeholder="поиск службы" value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="type-chips" style={{ marginTop: 10 }}>
        {all.filter((s) => !query || s.label.toLowerCase().includes(query.toLowerCase())).map((s) => {
          const isAuto = auto.has(s.code);
          const isChosen = chosen.includes(s.code);
          return (
            <button type="button" key={s.code} className={`chip ${isAuto || isChosen ? "selected" : ""}`} disabled={isAuto}
              title={isAuto ? "Подобрана автоматически — удалить нельзя" : undefined}
              onClick={() => setChosen(isChosen ? chosen.filter((c) => c !== s.code) : [...chosen, s.code])}>{s.label}</button>
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
