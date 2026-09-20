"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { api, type IncidentTypeItem, type Scenario, type ScenarioListItem } from "../../../lib/api";
import { categoryLabels, dateTime, sourceLabels } from "../../../lib/format";
import { ErrorBanner, Notice, useAction, useIncidentTypeLabels, useNotice } from "../common";

/** Развёрнутая карточка сценария: вводная целиком, адреса, эталон. Используется в списках. */
export function ScenarioCard({ scenario, typeLabel }: { scenario: ScenarioListItem; typeLabel: (id: string) => string }) {
  const a = scenario.expectedAddress;
  const expected = a ? [a.locality, a.street, a.house && `д. ${a.house}`, a.building && `к. ${a.building}`, a.structure && `стр. ${a.structure}`, a.apartment && `кв. ${a.apartment}`].filter(Boolean).join(", ") : "";
  return (
    <div className="scenario-details">
      <p><em>Вводная:</em> {scenario.callerText}</p>
      <p><em>Адрес со слов заявителя:</em> {scenario.rawAddress || "—"}</p>
      <p><em>Эталон адреса:</em> {expected || "не уточнён"}{a?.descriptive && !expected ? ` (ориентир: ${a.descriptive})` : ""}</p>
      <p><em>Тип:</em> {scenario.expectedIncidentTypes.map(typeLabel).join(", ") || "не задан"} · <em>Службы:</em> {scenario.expectedServices.join(", ") || "—"}</p>
      <p className="muted">{sourceLabels[scenario.source]} · {categoryLabels[scenario.category] ?? scenario.category} · сложность {scenario.difficulty} · {scenario.referenceConfirmed ? "эталон подтверждён" : "эталон не подтверждён"} · {scenario.id}</p>
    </div>
  );
}

/** Библиотека сценариев: билеты, сгенерированные, сформированные обучающимися; эталон и его подтверждение. */
export function Scenarios({ token }: { token: string }) {
  const [items, setItems] = useState<ScenarioListItem[]>([]);
  const [category, setCategory] = useState("");
  const [source, setSource] = useState("");
  const [selected, setSelected] = useState<Scenario | null>(null);
  const [types, setTypes] = useState<IncidentTypeItem[]>([]);
  const [genCount, setGenCount] = useState(3);
  const [search, setSearch] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const typeLabel = useIncidentTypeLabels(token);

  const load = useCallback(async () => {
    setItems(await api.teacher.scenarios(token, { category: category || undefined, source: source || undefined }));
  }, [token, category, source]);
  useEffect(() => { void run(load); }, [load, run]);
  useEffect(() => { api.incidentTypes(token).then(setTypes).catch(() => undefined); }, [token]);

  const open = (id: string) => run(async () => setSelected(await api.teacher.scenario(token, id)));
  const generate = () => run(async () => {
    const created = await api.teacher.generate(token, category || "FIRE", genCount, 5);
    setNotice(`Сгенерировано сценариев: ${created.length} — подтвердите эталон`);
    await load();
  });

  if (selected) {
    return <ScenarioEdit token={token} scenario={selected} types={types} onBack={() => { setSelected(null); void load(); }} />;
  }

  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Сценарии</b>
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="">все категории</option>
          {Object.entries(categoryLabels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <select value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="">все источники</option>
          <option value="TICKET">Билеты</option>
          <option value="GENERATED">Сгенерированные</option>
          <option value="TRAINEE_MADE">Сформированные обучающимися</option>
        </select>
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="поиск по названию и вводной" />
        <span className="spacer" />
        <label className="inline"><span>сгенерировать</span>
          <input type="number" min={1} max={20} value={genCount} onChange={(e) => setGenCount(Number(e.target.value) || 1)} className="w-xs" />
        </label>
        <button className="primary-button" disabled={working} onClick={generate}>Сгенерировать</button>
      </div>
      <table className="data-table">
        <thead><tr><th /><th>Название</th><th>Тип</th><th>Источник</th><th>Сложн.</th><th>Вводная</th><th>Эталон</th><th /></tr></thead>
        <tbody>
          {items.filter((s) => !search.trim() || [s.title, s.callerText, s.rawAddress ?? ""].some((v) => v.toLowerCase().includes(search.trim().toLowerCase()))).map((s) => (
            <Fragment key={s.id}>
              <tr className="clickable" onClick={() => setExpanded(expanded === s.id ? null : s.id)}>
                <td className="expand-cell">{expanded === s.id ? "⌃" : "⌄"}</td>
                <td><b>{s.title}</b></td>
                <td>{s.expectedIncidentTypes.map(typeLabel).join(", ") || <span className="muted">не задан</span>}</td>
                <td>{sourceLabels[s.source] ?? s.source}</td>
                <td>{s.difficulty}</td>
                <td className="ellipsis" title={s.callerText}>{s.callerText}</td>
                <td>{s.referenceConfirmed ? "✓" : <span className="muted">нет</span>}</td>
                <td><button className="ghost" onClick={(e) => { e.stopPropagation(); void open(s.id); }}>изменить</button></td>
              </tr>
              {expanded === s.id && <tr className="details-row"><td colSpan={8}><ScenarioCard scenario={s} typeLabel={typeLabel} /></td></tr>}
            </Fragment>
          ))}
        </tbody>
      </table>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function ScenarioEdit({ token, scenario, types, onBack }: { token: string; scenario: Scenario; types: IncidentTypeItem[]; onBack: () => void }) {
  const [s, setS] = useState<Scenario>(scenario);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const addr = s.expectedAddress ?? {};

  const save = () => run(async () => {
    setS(await api.teacher.updateScenario(token, s.id, {
      title: s.title, category: s.category, difficulty: s.difficulty, callerText: s.callerText, caller: s.caller,
      rawAddress: s.rawAddress, expectedAddress: s.expectedAddress, expectedIncidentTypes: s.expectedIncidentTypes,
      expectedServices: s.expectedServices, expectedDecision: s.expectedDecision,
      expectedDecisionReason: s.expectedDecisionReason, outboundCallRequired: s.outboundCallRequired,
    }));
    setNotice("Сценарий сохранён");
  });
  const confirm = () => run(async () => { setS(await api.teacher.confirmReference(token, s.id)); setNotice("Эталон подтверждён"); });
  const toggleType = (id: string) => setS({ ...s, expectedIncidentTypes: s.expectedIncidentTypes.includes(id) ? s.expectedIncidentTypes.filter((t) => t !== id) : [...s.expectedIncidentTypes, id], expectedServices: [] });

  return (
    <section className="panel-page">
      <div className="panel-head">
        <button className="ghost" onClick={onBack}>‹ сценарии</button>
        <b>{s.title}</b>
        <span className="muted">{sourceLabels[s.source]} · {s.referenceConfirmed ? `эталон подтверждён ${dateTime(s.referenceConfirmedAt)}` : "эталон не подтверждён"}</span>
        <span className="spacer" />
        {!s.referenceConfirmed && <button className="primary-button" disabled={working} onClick={confirm}>Подтвердить эталон</button>}
      </div>
      <div className="form-grid">
        <label className="wide"><span>Название (видит только преподаватель)</span>
          <input value={s.title} maxLength={200} onChange={(e) => setS({ ...s, title: e.target.value })} /></label>
        <label className="wide"><span>Вводная «со слов заявителя»</span>
          <textarea value={s.callerText} onChange={(e) => setS({ ...s, callerText: e.target.value })} /></label>
        <label><span>Категория</span>
          <select value={s.category} onChange={(e) => setS({ ...s, category: e.target.value })}>
            {Object.entries(categoryLabels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></label>
        <label><span>Сложность 1–10</span><input type="number" min={1} max={10} value={s.difficulty} onChange={(e) => setS({ ...s, difficulty: Number(e.target.value) || 1 })} /></label>
        <label className="wide"><span>Адрес со слов заявителя</span><input value={s.rawAddress ?? ""} onChange={(e) => setS({ ...s, rawAddress: e.target.value })} /></label>
      </div>

      <div className="panel-head"><b>Эталон: уточнённый адрес</b></div>
      <div className="form-grid four">
        {([["locality", "Населённый пункт"], ["street", "Улица"], ["house", "Дом"], ["building", "Корпус"], ["structure", "Строение"], ["apartment", "Квартира"], ["entrance", "Подъезд"], ["floor", "Этаж"]] as const).map(([key, title]) => (
          <label key={key}><span>{title}</span>
            <input value={(addr[key] as string | null) ?? ""} onChange={(e) => setS({ ...s, expectedAddress: { ...addr, [key]: e.target.value || null } })} /></label>
        ))}
      </div>

      <div className="panel-head"><b>Эталон: тип происшествия</b><small className="muted">службы подберутся по классификатору при сохранении</small></div>
      <div className="type-chips">
        {types.map((t) => (
          <button type="button" key={t.id} className={`chip ${s.expectedIncidentTypes.includes(t.id) ? "selected" : ""}`} onClick={() => toggleType(t.id)}>{t.label}</button>
        ))}
      </div>
      <p className="muted">Службы по ЕКП: {s.expectedServices.join(", ") || "—"}</p>

      <div className="panel-head"><b>Эталон: действия ДДС</b></div>
      <div className="form-grid">
        <label><span>Ожидаемое решение службы 101</span>
          <select value={s.expectedDecision ?? ""} onChange={(e) => setS({ ...s, expectedDecision: e.target.value || null })}>
            <option value="">по классификатору</option>
            <option value="ACCEPT">Принять</option>
            <option value="DECLINE">Не принимать</option>
          </select></label>
        <label><span>Обоснование отказа (если ожидается)</span><input value={s.expectedDecisionReason ?? ""} onChange={(e) => setS({ ...s, expectedDecisionReason: e.target.value || null })} /></label>
        <label className="check"><input type="checkbox" checked={s.outboundCallRequired} onChange={(e) => setS({ ...s, outboundCallRequired: e.target.checked })} /><span>Обязателен доклад руководителю (1102)</span></label>
      </div>
      <div className="dialog-actions"><button className="primary" disabled={working || !s.title.trim()} onClick={save}>Сохранить</button></div>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}
