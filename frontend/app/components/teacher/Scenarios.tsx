"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { api, type GenerationJob, type GenerationReport, type IncidentTypeItem, type Scenario, type ScenarioListItem } from "../../../lib/api";
import { categoryLabels, dateTime, sourceLabels } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, useAction, useIncidentTypeLabels, useNotice } from "../common";

/** Развёрнутая карточка сценария: вводная целиком, адреса, эталон с подсветкой правильных ответов. */
export function ScenarioCard({ scenario, typeLabel }: { scenario: ScenarioListItem; typeLabel: (id: string) => string }) {
  const a = scenario.expectedAddress;
  const expected = a ? [a.locality, a.street, a.house && `д. ${a.house}`, a.building && `к. ${a.building}`, a.structure && `стр. ${a.structure}`, a.apartment && `кв. ${a.apartment}`].filter(Boolean).join(", ") : "";
  return (
    <div className="scenario-details">
      <p><em>Вводная:</em> {scenario.callerText}</p>
      <p><em>Адрес со слов заявителя:</em> {scenario.rawAddress || "—"}</p>
      <div className="reference-preview">
        <p className="reference-title">Эталон (правильные ответы)</p>
        <p><em>Адрес:</em> <mark className="ref-ok">{expected || "не уточнён"}</mark>{a?.descriptive && !expected ? ` (ориентир: ${a.descriptive})` : ""}</p>
        <p><em>Тип:</em> {scenario.expectedIncidentTypes.length
          ? scenario.expectedIncidentTypes.map((id) => <mark key={id} className="ref-ok chip-inline">{typeLabel(id)}</mark>)
          : <span className="muted">не задан</span>}</p>
        <p><em>Службы:</em> {scenario.expectedServices.length
          ? scenario.expectedServices.map((code) => <mark key={code} className="ref-ok chip-inline">{code}</mark>)
          : "—"}</p>
      </div>
      <p className="muted">{sourceLabels[scenario.source]} · {categoryLabels[scenario.category] ?? scenario.category} · сложность {scenario.difficulty} · {scenario.referenceConfirmed ? "эталон подтверждён" : "эталон не подтверждён"} · {scenario.id}</p>
    </div>
  );
}

/** Причины отсева сгенерированных сценариев — коды ScenarioValidator словами. */
const rejectionLabels: Record<string, string> = {
  TYPE_CONTRADICTS_TEXT: "тип не следует из текста",
  TYPE_LEAKED: "подпись типа в тексте",
  TYPE_UNKNOWN: "тип не из классификатора",
  FOREIGN_SCRIPT: "чужие буквы",
  LATIN_IN_TEXT: "латиница в тексте",
  NOT_RUSSIAN: "текст не по-русски",
  SLOPPY_TEXT: "небрежный текст",
  ADDRESS_LEAKED: "адрес в тексте заявителя",
  STREET_UNKNOWN: "улицы нет в справочнике",
  CALLER_TEXT_TOO_SHORT: "слишком короткий текст",
};

function generationSummary(report: GenerationReport): string {
  if (report.generator === "none") {
    return "Языковая модель не подключена — новые сценарии не созданы. Готовые сценарии банка — в фильтре «Сгенерированные».";
  }
  const reasons = Object.entries(report.rejectionReasons)
    .map(([code, count]) => `${rejectionLabels[code] ?? code} — ${count}`).join(", ");
  const source = report.generator === "llm" ? "языковая модель" : report.generator;
  return `Источник: ${source}. Принято ${report.accepted} из ${report.produced}`
    + (report.rejected > 0 ? `, отсеяно ${report.rejected}: ${reasons}` : "")
    + (report.accepted > 0 ? ". Проверьте и подтвердите эталон." : ".");
}

/**
 * Сценарий, созданный генерацией из интерфейса и ещё не проверенный преподавателем.
 * Банк, поставляемый с системой (gen-seed-*), сюда не относится: он написан и проверен заранее.
 */
export function isFreshGenerated(s: ScenarioListItem) {
  return s.source === "GENERATED" && !s.referenceConfirmed && !s.id.startsWith("gen-seed-");
}

/**
 * Созданные до 28.09 перестановкой билетов (id gen-…): текст одного билета с адресом другого.
 * Их не выдаём за новые — у них своя пометка, чтобы преподаватель проверил или удалил их.
 */
function isOldRecombination(s: ScenarioListItem) {
  return isFreshGenerated(s) && s.id.startsWith("gen-");
}

/** Меньше стольких сценариев в категории — она помечается как тонкая. */
const THIN_CATEGORY = 10;

/**
 * Окно генерации: категория плитками с числом сценариев в библиотеке, количество, сложность.
 * Генерация идёт фоновой задачей; окно показывает ход и итог, а созданное отдаёт наверх,
 * чтобы список подсветил новую пачку.
 */
function GenerateDialog({ token, library, initialCategory, onClose, onDone }: {
  token: string;
  library: ScenarioListItem[];
  initialCategory: string;
  onClose: () => void;
  onDone: (savedIds: string[], category: string) => void;
}) {
  const [category, setCategory] = useState(initialCategory);
  const [count, setCount] = useState(5);
  const [difficulty, setDifficulty] = useState(5);
  const [generator, setGenerator] = useState<string | null>(null);
  const [job, setJob] = useState<GenerationJob | null>(null);
  const [startedAt, setStartedAt] = useState(0);
  const [now, setNow] = useState(0);
  const [error, setError] = useState("");

  useEffect(() => {
    api.teacher.generationStatus(token).then((s) => setGenerator(s.generator)).catch(() => setGenerator("none"));
  }, [token]);
  const running = job !== null && (job.state === "READY" || job.state === "RUNNING");
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  const counts = useMemo(() => {
    const map: Record<string, number> = {};
    for (const s of library) map[s.category] = (map[s.category] ?? 0) + 1;
    return map;
  }, [library]);
  const categories = Object.keys(categoryLabels).filter((k) => k !== "OTHER" || counts[k]);

  const start = async () => {
    setError("");
    try {
      let current = await api.teacher.startGeneration(token, category, count, difficulty);
      setStartedAt(Date.now());
      setNow(Date.now());
      setJob(current);
      for (let i = 0; i < 300 && (current.state === "READY" || current.state === "RUNNING"); i++) {
        await new Promise((resolve) => window.setTimeout(resolve, 2000));
        current = await api.teacher.generationJob(token, current.id);
        setJob(current);
      }
      if (current.report) onDone(current.report.savedIds, category);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Не удалось запустить генерацию");
      setJob(null);
    }
  };

  const report = job?.report ?? null;
  const elapsed = startedAt ? Math.max(0, Math.round((now - startedAt) / 1000)) : 0;
  const statusText = generator === null ? "Проверяем, подключена ли языковая модель…"
    : generator === "llm" ? "Языковая модель подключена: около 10 секунд на сценарий. Всё сгенерированное проходит проверку и попадает в библиотеку неподтверждённым."
    : generator === "none" ? "Языковая модель не подключена — новые сценарии создать нельзя. Готовые сценарии банка уже в библиотеке (источник «Сгенерированные»)."
    : "Модель не подключена, включён запасной способ — перестановка билетов с проверкой.";

  return (
    <Modal title="Генерация сценариев" onClose={onClose} wide>
      <div className="generate-dialog">
        <p className={`generator-status ${generator === "none" ? "off" : "on"}`}>{statusText}</p>

        <p className="field-title">Категория</p>
        <div className="category-tiles">
          {categories.map((k) => {
            const n = counts[k] ?? 0;
            return (
              <button key={k} type="button" disabled={running}
                className={`category-tile ${category === k ? "selected" : ""} ${n < THIN_CATEGORY ? "thin" : ""}`}
                onClick={() => setCategory(k)}>
                <b>{categoryLabels[k]}</b>
                <small>{n} в библиотеке{n < THIN_CATEGORY ? " · мало" : ""}</small>
              </button>
            );
          })}
        </div>

        <div className="generate-params">
          <label className="inline"><span>сколько</span>
            <input type="number" min={1} max={20} value={count} disabled={running}
              onChange={(e) => setCount(Math.min(20, Math.max(1, Number(e.target.value) || 1)))} className="w-xs" />
          </label>
          <label className="inline"><span>сложность</span>
            <input type="number" min={1} max={10} value={difficulty} disabled={running}
              onChange={(e) => setDifficulty(Math.min(10, Math.max(1, Number(e.target.value) || 5)))} className="w-xs" />
          </label>
        </div>

        {running && <p className="generate-progress">Генерация «{categoryLabels[category]}»: {elapsed} с… Окно можно свернуть — задача продолжится.</p>}
        {job?.state === "FAILED" && <p className="inline-error">Генерация не удалась: {job.error ?? "причина не указана"}</p>}
        {report && <p className="generate-result">{generationSummary(report)}</p>}
        {error && <p className="inline-error">{error}</p>}

        <div className="dialog-actions">
          {report && report.accepted > 0
            ? <button className="primary" onClick={onClose}>Показать новые ({report.accepted})</button>
            : (
              <button className="primary" disabled={!category || running || generator === "none" || generator === null} onClick={() => void start()}>
                {running ? "Генерация…" : category ? `Сгенерировать ${count} · ${categoryLabels[category]}` : "Выберите категорию"}
              </button>
            )}
          <button className="ghost" onClick={onClose}>{running ? "Свернуть" : "Закрыть"}</button>
        </div>
      </div>
    </Modal>
  );
}

/** Библиотека сценариев: билеты, сгенерированные, сформированные обучающимися; эталон и его подтверждение. */
export function Scenarios({ token }: { token: string }) {
  const [items, setItems] = useState<ScenarioListItem[]>([]);
  const [category, setCategory] = useState("");
  const [source, setSource] = useState("");
  const [selected, setSelected] = useState<Scenario | null>(null);
  const [types, setTypes] = useState<IncidentTypeItem[]>([]);
  const [library, setLibrary] = useState<ScenarioListItem[]>([]);
  const [dialog, setDialog] = useState(false);
  const [lastBatch, setLastBatch] = useState<string[]>([]);
  const [onlyFresh, setOnlyFresh] = useState(false);
  const [search, setSearch] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const typeLabel = useIncidentTypeLabels(token);

  const load = useCallback(async () => {
    setItems(await api.teacher.scenarios(token, { category: category || undefined, source: source || undefined }));
    // вся библиотека — для числа сценариев по категориям в окне генерации и счётчика новых
    setLibrary(await api.teacher.scenarios(token, {}));
  }, [token, category, source]);
  useEffect(() => { void run(load); }, [load, run]);
  useEffect(() => { api.incidentTypes(token).then(setTypes).catch(() => undefined); }, [token]);

  const open = (id: string) => run(async () => setSelected(await api.teacher.scenario(token, id)));
  const freshCount = library.filter(isFreshGenerated).length;
  const query = search.trim().toLowerCase();
  const visible = items
    .filter((s) => !onlyFresh || isFreshGenerated(s))
    .filter((s) => !query || [s.title, s.callerText, s.rawAddress ?? ""].some((v) => v.toLowerCase().includes(query)))
    // только что созданная пачка — наверх
    .sort((x, y) => Number(lastBatch.includes(y.id)) - Number(lastBatch.includes(x.id)));

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
        {freshCount > 0 && (
          <label className="inline fresh-filter">
            <input type="checkbox" checked={onlyFresh} onChange={(e) => setOnlyFresh(e.target.checked)} />
            <span>только новые, на проверке ({freshCount})</span>
          </label>
        )}
        <span className="spacer" />
        <button className="primary-button" disabled={working} onClick={() => setDialog(true)}>✦ Сгенерировать…</button>
      </div>
      <table className="data-table">
        <thead><tr><th /><th>Название</th><th>Тип</th><th>Источник</th><th>Сложн.</th><th>Вводная</th><th>Эталон</th><th /></tr></thead>
        <tbody>
          {visible.map((s) => (
            <Fragment key={s.id}>
              <tr className={`clickable ${lastBatch.includes(s.id) ? "just-generated" : ""}`} onClick={() => setExpanded(expanded === s.id ? null : s.id)}>
                <td className="expand-cell">{expanded === s.id ? "⌃" : "⌄"}</td>
                <td>
                  <b>{s.title}</b>
                  {isOldRecombination(s)
                    ? <span className="badge-new old" title="Создан старой перестановкой билетов: текст и адрес могут не сочетаться">склейка · проверьте</span>
                    : isFreshGenerated(s) && <span className="badge-new" title="Создан генерацией, эталон ещё не подтверждён">новый · на проверке</span>}
                </td>
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
      {dialog && (
        <GenerateDialog token={token} library={library} initialCategory={category}
          onClose={() => { setDialog(false); void load(); }}
          onDone={(ids, generated) => {
            setLastBatch(ids);
            // показать пачку там, где её видно: в её категории, без прочих фильтров
            if (ids.length > 0) { setCategory(generated); setSource(""); setOnlyFresh(false); setSearch(""); }
            if (ids.length > 0) setNotice(`Новых сценариев: ${ids.length} — подсвечены вверху списка`);
            void load();
          }} />
      )}
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
