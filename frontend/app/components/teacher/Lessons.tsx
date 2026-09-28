"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type CardSource, type CriterionScore, type Group, type Intensity, type Lesson, type LessonKind, type LessonMode, type LessonMonitor, type LessonReport, type ScenarioListItem, type SessionDetail } from "../../../lib/api";
import { actionLabels, categoryLabels, criterionLabels, criteriaForMode, dateTime, displayCardNumber, formatIssueValue, intensityHints, intensityLabels, kindLabels, lessonStateLabels, mmss, modeLabels, score, sessionStateLabels, sourceLabels, statusLabels } from "../../../lib/format";
import { ErrorBanner, Notice, useAction, useClock, useIncidentTypeLabels, useNotice, useSocket } from "../common";
import { BarChart, Donut, HorizontalBars, KpiGrid } from "../analytics/charts";
import { ScenarioCard } from "./Scenarios";
import { AssessmentView } from "../trainee/Results";

/** Занятия преподавателя: список, создание, монитор, отчёт, оценка. */
function localDay(iso: string) {
  const d = new Date(iso);
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

export function Lessons({ token }: { token: string }) {
  const [lessons, setLessons] = useState<Lesson[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [query, setQuery] = useState("");
  const [groupFilter, setGroupFilter] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [tab, setTab] = useState<"list" | "stats">("list");
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();

  const load = useCallback(async () => {
    try {
      setLessons(await api.teacher.lessons(token));
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Ошибка загрузки");
    }
  }, [token]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useEffect(() => {
    api.teacher.groups(token).then(setGroups).catch(() => undefined);
  }, [token]);

  const groupOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const group of groups) map.set(group.id, group.name);
    for (const lesson of lessons) {
      if (lesson.groupId && lesson.groupName && !map.has(lesson.groupId)) map.set(lesson.groupId, lesson.groupName);
    }
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1], "ru"));
  }, [groups, lessons]);
  const hasUngrouped = lessons.some((lesson) => !lesson.groupId);
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return lessons.filter((lesson) => {
      if (q && !lesson.title.toLowerCase().includes(q)) return false;
      if (groupFilter === "none" && lesson.groupId) return false;
      if (groupFilter && groupFilter !== "none" && lesson.groupId !== groupFilter) return false;
      const day = localDay(lesson.createdAt);
      if (dateFrom && day < dateFrom) return false;
      if (dateTo && day > dateTo) return false;
      return true;
    });
  }, [lessons, query, groupFilter, dateFrom, dateTo]);
  const filtersOn = query.trim() !== "" || groupFilter !== "" || dateFrom !== "" || dateTo !== "";

  if (creating) {
    return <LessonCreate token={token} onDone={(lesson) => { setCreating(false); setSelected(lesson.id); void load(); setNotice("Занятие создано"); }} onCancel={() => setCreating(false)} />;
  }
  if (selected) {
    return <LessonView token={token} lessonId={selected} onBack={() => { setSelected(null); void load(); }} />;
  }
  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Занятия</b>
        <div className="sub-nav" role="tablist">
          <button type="button" role="tab" aria-selected={tab === "list"} className={tab === "list" ? "active" : ""} onClick={() => setTab("list")}>Список</button>
          <button type="button" role="tab" aria-selected={tab === "stats"} className={tab === "stats" ? "active" : ""} onClick={() => setTab("stats")}>Статистика</button>
        </div>
        <span className="spacer" />
        <button className="primary-button" onClick={() => setCreating(true)}>Новое занятие</button>
      </div>
      <div className="panel-head lesson-filters">
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="поиск по названию" aria-label="Поиск по названию" />
        <select value={groupFilter} onChange={(e) => setGroupFilter(e.target.value)} aria-label="Группа">
          <option value="">все группы</option>
          {groupOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          {hasUngrouped && <option value="none">без группы</option>}
        </select>
        <label className="inline"><span>дата с</span>
          <input type="date" value={dateFrom} max={dateTo || undefined} onChange={(e) => setDateFrom(e.target.value)} aria-label="Дата с" />
        </label>
        <label className="inline"><span>по</span>
          <input type="date" value={dateTo} min={dateFrom || undefined} onChange={(e) => setDateTo(e.target.value)} aria-label="Дата по" />
        </label>
        {filtersOn && <button type="button" className="ghost" onClick={() => { setQuery(""); setGroupFilter(""); setDateFrom(""); setDateTo(""); }}>сбросить</button>}
        <small className="muted">{filtered.length} из {lessons.length}</small>
      </div>
      {tab === "stats" ? <LessonStats lessons={filtered} /> : (
      <table className="data-table">
        <thead>
          <tr><th>Создано</th><th>Название</th><th>Группа</th><th>Вид</th><th>Режим</th><th>Поток</th><th>Участников</th><th>Состояние</th></tr>
        </thead>
        <tbody>
          {filtered.length === 0 && (
            <tr><td colSpan={8} className="muted">{lessons.length === 0 ? "Занятий ещё нет — создайте первое" : "Нет занятий по заданным условиям"}</td></tr>
          )}
          {filtered.map((l) => (
            <tr key={l.id} className="clickable" onClick={() => setSelected(l.id)}>
              <td>{dateTime(l.createdAt)}</td>
              <td><b>{l.title}</b></td>
              <td>{l.groupName ?? "—"}</td>
              <td>{kindLabels[l.kind]}</td>
              <td>{modeLabels[l.mode]}{l.mode === "CARD_ACTIONS" ? ` · служба ${l.serviceCode}` : ""}</td>
              <td>{intensityLabels[l.intensity] ?? l.intensity}</td>
              <td>{l.sessionCount}</td>
              <td><span className={`state-pill ${l.state.toLowerCase()}`}>{lessonStateLabels[l.state]}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function countBy(lessons: Lesson[], key: (lesson: Lesson) => string) {
  const map = new Map<string, number>();
  for (const lesson of lessons) {
    const id = key(lesson);
    map.set(id, (map.get(id) ?? 0) + 1);
  }
  return map;
}

/** Мини-дашборд по текущей выборке занятий (учитывает фильтры списка). */
function LessonStats({ lessons }: { lessons: Lesson[] }) {
  const participants = lessons.reduce((sum, lesson) => sum + lesson.sessionCount, 0);
  const active = lessons.filter((lesson) => lesson.state === "ACTIVE").length;
  const completed = lessons.filter((lesson) => lesson.state === "COMPLETED").length;
  const drafts = lessons.filter((lesson) => lesson.state === "DRAFT").length;
  const groups = new Set(lessons.map((lesson) => lesson.groupId ?? "none")).size;

  const stateMix = [
    { key: "draft", value: drafts, color: "#8e9ca3", label: "Не начато" },
    { key: "active", value: active, color: "#2f9a5a", label: "Идёт" },
    { key: "done", value: completed, color: "#0784c6", label: "Завершено" },
  ];

  const byKind = (["TRAINING", "CHECK", "EXAM"] as LessonKind[]).map((kind) => ({
    key: kind,
    label: kindLabels[kind],
    value: lessons.filter((lesson) => lesson.kind === kind).length,
    color: kind === "EXAM" ? "#0c6fa4" : kind === "CHECK" ? "#5a6a72" : "#2f9a5a",
  }));
  const byMode = (["CARD_FILL", "CARD_ACTIONS"] as LessonMode[]).map((mode) => ({
    key: mode,
    label: modeLabels[mode],
    value: lessons.filter((lesson) => lesson.mode === mode).length,
    color: mode === "CARD_FILL" ? "#0784c6" : "#b06a2e",
  }));
  const byGroup = [...countBy(lessons, (lesson) => lesson.groupName ?? "Без группы").entries()]
    .map(([label, value]) => ({ key: label, label, value, color: "#0784c6" }))
    .sort((a, b) => b.value - a.value);

  const byDay = useMemo(() => {
    const counts = countBy(lessons, (lesson) => localDay(lesson.createdAt));
    return [...counts.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .slice(-14)
      .map(([day, value]) => ({
        key: day,
        label: day.slice(8),
        value,
        hint: day,
        color: "#0784c6",
      }));
  }, [lessons]);
  const dayMax = Math.max(1, ...byDay.map((item) => item.value));

  if (lessons.length === 0) {
    return <p className="muted">Нет занятий для статистики — измените фильтры или создайте занятие.</p>;
  }

  return (
    <div className="analytics-page">
      <KpiGrid items={[
        { label: "Занятий", value: String(lessons.length), hint: "В текущей выборке" },
        { label: "Участников", value: String(participants), hint: "Сумма мест по занятиям" },
        { label: "Идут сейчас", value: String(active), tone: active > 0 ? "ok" : "neutral" },
        { label: "Групп", value: String(groups), hint: `Завершено ${completed}` },
      ]} />
      <div className="analytics-grid">
        <article className="analytics-card">
          <header><b>По состоянию</b></header>
          <Donut center={String(lessons.length)} segments={stateMix} size={280} />
          <p className="chart-note">Доли по количеству занятий</p>
        </article>
        <article className="analytics-card">
          <header><b>Создано по дням</b><small className="muted">до 14 последних</small></header>
          <BarChart ariaLabel="Число занятий по дням создания" items={byDay} scale="count" max={dayMax} />
          <p className="chart-note">Количество созданных занятий за день</p>
        </article>
        <article className="analytics-card">
          <header><b>По группам</b></header>
          <HorizontalBars items={byGroup} scale="count" unit="зан." />
          <p className="chart-note">Сколько занятий на группу (полная полоса = максимум в списке)</p>
        </article>
        <article className="analytics-card">
          <header><b>Вид и режим</b></header>
          <HorizontalBars items={[...byKind, ...byMode]} scale="count" unit="зан." />
          <p className="chart-note">Количество занятий. Сначала вид, ниже — режим карточки</p>
        </article>
      </div>
    </div>
  );
}

function LessonCreate({ token, onDone, onCancel }: { token: string; onDone: (lesson: Lesson) => void; onCancel: () => void }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [scenarios, setScenarios] = useState<ScenarioListItem[]>([]);
  const [title, setTitle] = useState("");
  const [groupId, setGroupId] = useState<string>("");
  const [kind, setKind] = useState<LessonKind>("TRAINING");
  const [mode, setMode] = useState<LessonMode>("CARD_FILL");
  const [cardSource, setCardSource] = useState<CardSource>("GENERATED");
  const [intensity, setIntensity] = useState<Intensity>("MEDIUM");
  const [serviceCode, setServiceCode] = useState("101");
  const [services, setServices] = useState<Array<{ code: string; label: string }>>([]);
  const [showForeign, setShowForeign] = useState(false);
  const [category, setCategory] = useState("");
  const [difficultyMin, setDifficultyMin] = useState(1);
  const [difficultyMax, setDifficultyMax] = useState(10);
  const [normScore, setNormScore] = useState(60);
  const [chosenScenarios, setChosenScenarios] = useState<string[]>([]);
  const [chosenTrainees, setChosenTrainees] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [working, run] = useAction(setError);
  const typeLabel = useIncidentTypeLabels(token);

  useEffect(() => {
    api.teacher.groups(token).then((g) => { setGroups(g); if (g[0]) setGroupId(g[0].id); }).catch((e) => setError(e.message));
    api.references(token).then((r) => setServices(r.services.map((s) => ({ code: s.code, label: s.label })))).catch(() => undefined);
  }, [token]);
  useEffect(() => {
    const source = cardSource === "MIXED" ? undefined : cardSource === "TRAINEE_MADE" ? "TRAINEE_MADE" : undefined;
    api.teacher.scenarios(token, { category: category || undefined, source }).then((list) => {
      setScenarios(cardSource === "GENERATED" ? list.filter((s) => s.source !== "TRAINEE_MADE") : list);
    }).catch((e) => setError(e.message));
  }, [token, category, cardSource]);

  const group = groups.find((g) => g.id === groupId);
  const members = group?.members ?? [];
  // Режим действий: по умолчанию только сценарии, где выбранная служба оповещается по ЕКП;
  // непрофильные (для отработки «Не принята») — по отдельному флажку.
  const isForeign = useCallback((s: ScenarioListItem) => mode === "CARD_ACTIONS" && !s.expectedServices.includes(serviceCode), [mode, serviceCode]);
  const visibleScenarios = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = scenarios.filter((s) => {
      if (s.difficulty < difficultyMin || s.difficulty > difficultyMax) return false;
      if (!showForeign && isForeign(s)) return false;
      if (!q) return true;
      return [s.title, s.callerText, s.rawAddress ?? ""].some((v) => v.toLowerCase().includes(q));
    });
    return [...list].sort((a, b) => a.difficulty - b.difficulty || Number(b.referenceConfirmed) - Number(a.referenceConfirmed) || a.title.localeCompare(b.title, "ru"));
  }, [scenarios, search, difficultyMin, difficultyMax, showForeign, isForeign]);
  const foreignCount = useMemo(() => scenarios.filter(isForeign).length, [scenarios, isForeign]);
  const toggle = (list: string[], id: string, set: (v: string[]) => void) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

  const submit = () => run(async () => {
    const lesson = await api.teacher.createLesson(token, {
      title: title.trim() || `${kindLabels[kind]} · ${modeLabels[mode]}`,
      groupId: groupId || null, kind, mode, cardSource, intensity,
      serviceCode: mode === "CARD_ACTIONS" ? serviceCode : null,
      scenarioIds: chosenScenarios, traineeIds: chosenTrainees,
      normScore,
    });
    onDone(lesson);
  });

  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Новое занятие</b>
        <button className="ghost" onClick={onCancel}>‹ к списку</button>
      </div>
      <div className="form-grid">
        <label><span>Название</span><input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="например, Билет 3 — пожары" /></label>
        <label><span>Группа</span>
          <select value={groupId} onChange={(e) => { setGroupId(e.target.value); setChosenTrainees([]); }}>
            {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
          </select></label>
        <label><span>Вид</span>
          <select value={kind} onChange={(e) => setKind(e.target.value as LessonKind)}>
            {(["TRAINING", "CHECK", "EXAM"] as LessonKind[]).map((k) => <option key={k} value={k}>{kindLabels[k]}</option>)}
          </select>
          <small>{kind === "TRAINING" ? "подсказки по ходу" : kind === "CHECK" ? "результат сразу по окончании" : "результат после вашей проверки и публикации"}</small></label>
        <label><span>Режим</span>
          <select value={mode} onChange={(e) => setMode(e.target.value as LessonMode)}>
            {(["CARD_FILL", "CARD_ACTIONS"] as LessonMode[]).map((m) => <option key={m} value={m}>{modeLabels[m]}</option>)}
          </select>
          <small>{mode === "CARD_FILL"
            ? "оператор 112: журнал смены, входящие вызовы, карточка по принятому вызову"
            : "диспетчер ДДС: карточки в журнале, статусы реагирования, доклад руководителю"}</small></label>
        <label><span>Интенсивность</span>
          <select value={intensity} onChange={(e) => setIntensity(e.target.value as Intensity)}>
            {(["SEQUENTIAL", "LOW", "MEDIUM", "HIGH"] as Intensity[]).map((i) => <option key={i} value={i}>{intensityLabels[i]}</option>)}
          </select>
          <small>{intensityHints[intensity]}</small></label>
        <label><span>Норма, балл</span>
          <input type="number" min={0} max={100} value={normScore} onChange={(e) => setNormScore(Math.min(100, Math.max(0, Number(e.target.value) || 0)))} />
          <small>на графиках линия: балл выше этого значения — норма</small></label>
        {mode === "CARD_ACTIONS" && (
          <label><span>Служба обучающегося</span>
            <select value={serviceCode} onChange={(e) => { setServiceCode(e.target.value); setChosenScenarios([]); }}>
              {services.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
            </select>
            <small>за эту службу обучающийся принимает карточки и проставляет статусы</small></label>
        )}
        {mode === "CARD_ACTIONS" && (
          <label><span>Источник карточек</span>
            <select value={cardSource} onChange={(e) => setCardSource(e.target.value as CardSource)}>
              {(["GENERATED", "TRAINEE_MADE", "MIXED"] as CardSource[]).map((s) => <option key={s} value={s}>{sourceLabels[s]}</option>)}
            </select></label>
        )}
      </div>

      <div className="panel-head"><b>Сценарии</b>
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="">все категории</option>
          {Object.entries(categoryLabels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="inline"><span>сложность</span>
          <input type="number" min={1} max={10} value={difficultyMin} onChange={(e) => setDifficultyMin(Math.min(10, Math.max(1, Number(e.target.value) || 1)))} className="w-xs" />
          <span>—</span>
          <input type="number" min={1} max={10} value={difficultyMax} onChange={(e) => setDifficultyMax(Math.min(10, Math.max(1, Number(e.target.value) || 10)))} className="w-xs" />
        </label>
        <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="поиск по названию, вводной, адресу" />
        {mode === "CARD_ACTIONS" && (
          <label className="inline"><input type="checkbox" checked={showForeign} onChange={(e) => setShowForeign(e.target.checked)} />
            <span>показать непрофильные для службы {serviceCode} ({foreignCount}) — для отработки «Не принята»</span></label>
        )}
        <small>выбрано: {chosenScenarios.length} из {visibleScenarios.length}</small>
      </div>
      <div className="check-list scenarios">
        {visibleScenarios.map((s) => (
          <div key={s.id} className={`scenario-row ${chosenScenarios.includes(s.id) ? "on" : ""}`}>
            <label>
              <input type="checkbox" checked={chosenScenarios.includes(s.id)} onChange={() => toggle(chosenScenarios, s.id, setChosenScenarios)} />
              <b>{s.title}</b>
              <em>{s.expectedIncidentTypes.map(typeLabel).join(", ") || categoryLabels[s.category] || s.category} · сложность {s.difficulty}{s.referenceConfirmed ? " · эталон ✓" : ""}{isForeign(s) ? ` · непрофильная для ${serviceCode}` : ""}</em>
              <span>{s.callerText}</span>
            </label>
            <button type="button" className="expand" aria-label="Подробнее" onClick={() => setExpanded(expanded === s.id ? null : s.id)}>{expanded === s.id ? "⌃" : "⌄"}</button>
            {expanded === s.id && <ScenarioCard scenario={s} typeLabel={typeLabel} />}
          </div>
        ))}
      </div>

      <div className="panel-head"><b>Обучающиеся</b><small>выбрано: {chosenTrainees.length}</small></div>
      <div className="check-list compact">
        {members.length === 0 && <p className="muted">В группе нет обучающихся — попросите администратора добавить их в группу.</p>}
        {members.map((m) => (
          <label key={m.id} className={chosenTrainees.includes(m.id) ? "on" : ""}>
            <input type="checkbox" checked={chosenTrainees.includes(m.id)} onChange={() => toggle(chosenTrainees, m.id, setChosenTrainees)} />
            <b>АРМ {m.workstationNumber ?? "—"}</b><span>{m.displayName}</span>
          </label>
        ))}
        {members.length > 0 && <button className="ghost" onClick={() => setChosenTrainees(members.map((m) => m.id))}>выбрать всех</button>}
      </div>

      <div className="dialog-actions">
        <button className="primary" disabled={working || chosenScenarios.length === 0 || chosenTrainees.length === 0} onClick={submit}>Создать</button>
      </div>
      <ErrorBanner error={error} onClose={() => setError("")} />
    </section>
  );
}

function LessonView({ token, lessonId, onBack }: { token: string; lessonId: string; onBack: () => void }) {
  const [monitor, setMonitor] = useState<LessonMonitor | null>(null);
  const [report, setReport] = useState<LessonReport | null>(null);
  const [session, setSession] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const now = useClock();

  const load = useCallback(async () => {
    try {
      const m = await api.teacher.monitor(token, lessonId);
      setMonitor(m);
      if (m.lesson.state !== "DRAFT") setReport(await api.teacher.report(token, lessonId));
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Ошибка загрузки");
    }
  }, [token, lessonId]);

  useEffect(() => { void Promise.resolve().then(load); }, [load]);
  useSocket(token, () => { void load(); });
  useEffect(() => {
    if (monitor?.lesson.state !== "ACTIVE") return;
    const timer = window.setInterval(() => { void load(); }, 5000);
    return () => window.clearInterval(timer);
  }, [monitor?.lesson.state, load]);

  const lesson = monitor?.lesson;
  const allAssessed = useMemo(() => (report?.rows ?? []).every((r) => r.finalTotal !== null), [report]);

  if (session) {
    return <SessionReview token={token} sessionId={session} onBack={() => { setSession(null); void load(); }} lessonKind={lesson?.kind ?? "CHECK"} />;
  }
  if (!lesson) return <div className="boot-screen">Загрузка занятия…</div>;

  const start = () => run(async () => { await api.teacher.startLesson(token, lessonId); setNotice("Занятие начато — карточки ушли на рабочие места"); await load(); });
  const complete = () => run(async () => { await api.teacher.completeLesson(token, lessonId); setNotice("Занятие завершено"); await load(); });
  const publish = () => run(async () => { await api.teacher.publishLesson(token, lessonId); setNotice("Результаты опубликованы"); await load(); });
  const csv = () => run(async () => {
    const blob = await api.teacher.reportCsv(token, lessonId);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = `lesson-${lessonId}.csv`; a.click();
    URL.revokeObjectURL(url);
  });

  return (
    <section className="panel-page">
      <div className="panel-head">
        <button className="ghost" onClick={onBack}>‹ занятия</button>
        <b>{lesson.title}</b>
        <span className="muted">{kindLabels[lesson.kind]} · {modeLabels[lesson.mode]}{lesson.mode === "CARD_ACTIONS" ? ` · служба ${lesson.serviceCode}` : ""} · поток: {intensityLabels[lesson.intensity] ?? lesson.intensity} · {lesson.groupName ?? "без группы"} · <span className={`state-pill ${lesson.state.toLowerCase()}`}>{lessonStateLabels[lesson.state]}</span></span>
        <span className="spacer" />
        {lesson.state === "DRAFT" && <button className="primary-button" disabled={working} onClick={start}>Начать</button>}
        {lesson.state === "ACTIVE" && <button className="danger-button" disabled={working} onClick={complete}>Завершить занятие</button>}
        {lesson.state === "COMPLETED" && lesson.kind === "EXAM" && !lesson.resultsPublishedAt && (
          <button className="primary-button" disabled={working || !allAssessed} title={allAssessed ? undefined : "Сначала оцените всех участников"} onClick={publish}>Опубликовать результаты</button>
        )}
        {lesson.resultsPublishedAt && <small className="muted">опубликовано {dateTime(lesson.resultsPublishedAt)}</small>}
      </div>

      {!(report && report.rows.some((r) => r.finalTotal !== null)) && (
      <table className="data-table monitor">
        <thead>
          <tr><th>АРМ</th><th>Обучающийся</th><th>Состояние</th><th>Текущий статус</th><th>Карточек</th><th>Время</th><th>Итог</th></tr>
        </thead>
        <tbody>
          {monitor.sessions.map((s) => (
            <tr key={s.sessionId} className={`clickable ${s.acceptanceOverdue || s.processingOverdue ? "overdue" : ""}`} onClick={() => setSession(s.sessionId)}>
              <td><b>{s.workstationNumber ?? "—"}</b></td>
              <td>{s.traineeName}</td>
              <td>{sessionStateLabels[s.state] ?? s.state}</td>
              <td className="status-cell">{s.currentStatus ? (statusLabels[s.currentStatus] ?? (s.currentStatus === "DRAFT" ? "Заполняет" : s.currentStatus === "SAVED" ? "Сохранена" : s.currentStatus)) : "—"}{s.acceptanceOverdue ? " · просрочено принятие" : s.processingOverdue ? " · просрочена отработка" : ""}</td>
              <td>{s.completedCards} / {s.totalCards}</td>
              <td>{s.startedAt && s.state === "ACTIVE" ? mmss(Math.max(0, Math.floor((now - new Date(s.startedAt).getTime()) / 1000))) : s.completedAt ? dateTime(s.completedAt) : "—"}</td>
              <td><b>{score(s.finalTotal)}</b></td>
            </tr>
          ))}
        </tbody>
      </table>
      )}

      {report && report.rows.some((r) => r.finalTotal !== null) && (
        <>
          <div className="panel-head">
            <b>Отчёт</b>
            <small className="muted">рейтинг занятия {score(report.groupRating)} · норма {lesson.normScore}</small>
            <span className="spacer" />
            <button className="ghost" onClick={csv}>выгрузить CSV</button>
          </div>
          <ReportCharts report={report} />
          <table className="data-table">
            <thead>
              <tr><th>Обучающийся</th><th>АРМ</th><th>Время</th><th>Синт. ошибок</th><th>Уровень</th><th>Оценка ИИ</th><th>Оценка преп.</th><th>Итог</th></tr>
            </thead>
            <tbody>
              {report.rows.map((r) => (
                <tr key={r.sessionId} className="clickable" onClick={() => setSession(r.sessionId)}>
                  <td>{r.traineeName}</td><td>{r.workstationNumber ?? "—"}</td>
                  <td>{score(r.timingScore)}</td><td>{r.syntaxErrors ?? "—"}</td><td>{score(r.level)}</td>
                  <td>{score(r.aiTotal)}</td><td>{score(r.teacherTotal)}</td><td><b>{score(r.finalTotal)}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function personName(name: string) {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length >= 2) return `${parts[0]} ${parts[1]}`;
  return parts[0] ?? "—";
}

/** Две диаграммы: итог и время, подписи — фамилия и имя, линия нормы занятия. */
function ReportCharts({ report }: { report: LessonReport }) {
  const rows = report.rows.filter((r) => r.finalTotal !== null);
  if (rows.length === 0) return null;
  const norm = report.lesson.normScore ?? 60;
  const width = Math.max(480, rows.length * 78);
  const height = 220;
  const padL = 36;
  const padR = 12;
  const padT = 22;
  const padB = 78;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;
  const slot = plotW / rows.length;
  const bar = Math.max(10, Math.min(28, slot - 16));
  const yOf = (value: number) => padT + plotH - (Math.max(0, Math.min(100, value)) / 100) * plotH;
  const normY = yOf(norm);

  const chart = (title: string, aria: string, valueOf: (row: LessonReport["rows"][number]) => number | null) => (
    <figure>
      <figcaption>{title}</figcaption>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={aria}>
        <line x1={padL} y1={height - padB} x2={width - padR} y2={height - padB} stroke="#8e9ca3" />
        <line x1={padL} y1={normY} x2={width - padR} y2={normY} stroke="#b03f2e" strokeDasharray="5 3" />
        <text x={padL + 4} y={normY - 4} fontSize="11" fill="#b03f2e">норма {norm}</text>
        {rows.map((r, i) => {
          const value = valueOf(r) ?? 0;
          const h = (Math.max(0, Math.min(100, value)) / 100) * plotH;
          const x = padL + i * slot + (slot - bar) / 2;
          const label = personName(r.traineeName);
          return (
            <g key={r.sessionId}>
              <rect x={x} y={height - padB - h} width={bar} height={Math.max(1, h)} fill={value >= norm ? "#2f9a5a" : "#b03f2e"} />
              <text x={x + bar / 2} y={height - padB - h - 4} fontSize="11" textAnchor="middle" fill="#172026">{score(value)}</text>
              <text x={x + bar / 2} y={height - padB + 14} fontSize="11" textAnchor="end" fill="#24313a" transform={`rotate(-40 ${x + bar / 2} ${height - padB + 14})`}>{label}</text>
            </g>
          );
        })}
      </svg>
    </figure>
  );

  return (
    <div className="charts">
      {chart("Итоговые баллы", "Итоговые баллы по обучающимся", (r) => r.finalTotal)}
      {chart("Оценка по времени", "Оценка по времени", (r) => r.timingScore)}
    </div>
  );
}

type CriterionDraft = { score: string; comment: string };

function ReviewIssues({ detail }: { detail: SessionDetail }) {
  const issues = detail.assessment?.issues ?? [];
  const titleOf = (cardId: string | null) => {
    if (!cardId) return "По занятию";
    const draft = detail.drafts.find((item) => item.id === cardId);
    if (draft) return `${draft.scenarioTitle ?? "Карточка"} · № ${displayCardNumber(draft.number)}`;
    const card = detail.cards.find((item) => item.id === cardId);
    if (card) return `${card.scenarioTitle ?? "Карточка"} · № ${displayCardNumber(card.number)}`;
    return "Карточка";
  };
  const groups = new Map<string, typeof issues>();
  for (const issue of issues) {
    const key = issue.cardId ?? "";
    const list = groups.get(key) ?? [];
    list.push(issue);
    groups.set(key, list);
  }
  const known = [...detail.drafts.map((item) => item.id), ...detail.cards.map((item) => item.id), ""];
  const keys = [
    ...known.filter((id) => groups.has(id)),
    ...[...groups.keys()].filter((id) => !known.includes(id)),
  ];

  return (
    <>
      <div className="panel-head"><b>Замечания по карточкам</b></div>
      {keys.length === 0 && <p className="muted">Ошибок и замечаний по тексту нет</p>}
      {keys.map((key) => {
        const list = groups.get(key) ?? [];
        const errors = list.filter((issue) => issue.severity !== "INFO");
        const notes = list.filter((issue) => issue.severity === "INFO");
        return (
          <article key={key || "session"} className="review-card">
            <b>{titleOf(key || null)}</b>
            <p><em>Ошибки</em></p>
            {errors.length === 0 ? <p>нет</p> : (
              <ul className="issue-list">
                {errors.map((issue, index) => {
                  const expected = formatIssueValue(issue.expected);
                  const actual = formatIssueValue(issue.actual);
                  return (
                    <li key={`${issue.code}-${index}`}>
                      {issue.message}
                      {issue.severity === "CRITICAL" && <span className="issue-tag">критично</span>}
                      {(expected || actual) && (
                        <small>
                          {expected && <>эталон: {expected}</>}
                          {expected && actual && " · "}
                          {actual && <>факт: {actual}</>}
                        </small>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            <p><em>Замечания по тексту</em></p>
            {notes.length === 0 ? <p>нет</p> : (
              <ul className="issue-list">
                {notes.map((issue, index) => <li key={`${issue.code}-${index}`}>{issue.message}</li>)}
              </ul>
            )}
          </article>
        );
      })}
    </>
  );
}


function SessionReview({ token, sessionId, onBack, lessonKind }: { token: string; sessionId: string; onBack: () => void; lessonKind: LessonKind }) {
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [comment, setComment] = useState("");
  const [criteria, setCriteria] = useState<Record<string, CriterionDraft>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const typeLabel = useIncidentTypeLabels(token);

  const load = useCallback(async () => {
    const d = await api.teacher.session(token, sessionId);
    setDetail(d);
    if (d.assessment?.teacherComment) setComment(d.assessment.teacherComment);
    const drafts: Record<string, CriterionDraft> = {};
    for (const c of d.assessment?.teacherCriteria ?? []) {
      drafts[c.code] = { score: c.score === null ? "" : String(c.score), comment: c.comment ?? "" };
    }
    setCriteria(drafts);
  }, [token, sessionId]);
  useEffect(() => { void run(load); }, [load, run]);

  if (!detail) return <div className="boot-screen">Загрузка…</div>;
  const mode = detail.assessment?.mode ?? detail.session.mode ?? "CARD_FILL";
  const codes = criteriaForMode(mode);
  const aiScore = (code: string) => detail.assessment?.aiCriteria.find((c) => c.code === code)?.score ?? null;
  const setCriterion = (code: string, patch: Partial<CriterionDraft>) =>
    setCriteria({ ...criteria, [code]: { ...(criteria[code] ?? { score: "", comment: "" }), ...patch } });
  // предпросмотр итога по весам: балл преподавателя там, где введён, иначе балл ИИ
  const previewTotal = () => {
    let sum = 0, weights = 0;
    for (const { code, weight } of codes) {
      const draft = criteria[code];
      const value = draft && draft.score !== "" ? Number(draft.score) : aiScore(code);
      if (value === null || Number.isNaN(value)) continue;
      sum += value * weight;
      weights += weight;
    }
    return weights ? Math.round((sum / weights) * 10) / 10 : null;
  };
  const invalid = codes.some(({ code }) => {
    const v = criteria[code]?.score ?? "";
    return v !== "" && (Number.isNaN(Number(v)) || Number(v) < 0 || Number(v) > 100);
  });
  const anyScore = codes.some(({ code }) => (criteria[code]?.score ?? "") !== "");
  const assess = () => run(async () => {
    const payload: CriterionScore[] = codes
      .filter(({ code }) => criteria[code] && (criteria[code].score !== "" || criteria[code].comment.trim() !== ""))
      .map(({ code }) => ({ code, score: criteria[code].score === "" ? null : Number(criteria[code].score), comment: criteria[code].comment.trim() || null }));
    await api.teacher.assess(token, sessionId, anyScore ? null : detail.assessment?.totalScore ?? null, comment, payload);
    setNotice("Оценка сохранена — итог пересчитан по критериям");
    await load();
  });
  const confirmAi = () => run(async () => {
    const payload: CriterionScore[] = codes
      .map(({ code }) => ({ code, score: aiScore(code), comment: null }))
      .filter((criterion): criterion is CriterionScore => criterion.score !== null);
    await api.teacher.assess(token, sessionId, null, comment, payload);
    setNotice("Оценка системы подтверждена преподавателем и учтена для коррекции ИИ");
    await load();
  });
  const fmtAddress = (a: SessionDetail["drafts"][number]["address"]) => {
    const formal = [a.locality, a.street, a.house && `д. ${a.house}`, a.building && `к. ${a.building}`, a.apartment && `кв. ${a.apartment}`].filter(Boolean).join(", ");
    if (formal) return a.descriptive ? `${formal} (ориентир: ${a.descriptive})` : formal;
    return a.descriptive ? `не формализован; ориентир: ${a.descriptive}` : "не указан";
  };

  return (
    <section className="panel-page">
      <div className="panel-head">
        <button className="ghost" onClick={onBack}>‹ к занятию</button>
        <b>{detail.trainee?.displayName ?? "Обучающийся"}</b>
        <span className="muted">АРМ {detail.trainee?.workstationNumber ?? "—"} · {detail.session.lessonTitle} · {kindLabels[lessonKind]}</span>
      </div>

      {detail.assessment ? (
        <>
          <AssessmentView assessment={detail.assessment} title="Оценка системы и итог" hideIssues />
          <ReviewIssues detail={detail} />
          <div className="panel-head"><b>Оценка преподавателя по критериям</b><small className="muted">пустой балл — остаётся балл системы; итог считается по весам режима</small></div>
          <table className="data-table criteria">
            <thead><tr><th>Критерий</th><th>Вес</th><th>Балл системы</th><th>Балл преподавателя</th><th>Комментарий обучающемуся</th></tr></thead>
            <tbody>
              {codes.map(({ code, weight }) => (
                <tr key={code}>
                  <td><b>{criterionLabels[code] ?? code}</b></td>
                  <td>{weight}%</td>
                  <td>{score(aiScore(code))}</td>
                  <td><input type="number" min={0} max={100} step={0.5} className="w-xs" value={criteria[code]?.score ?? ""} onChange={(e) => setCriterion(code, { score: e.target.value })} /></td>
                  <td><input value={criteria[code]?.comment ?? ""} onChange={(e) => setCriterion(code, { comment: e.target.value })} placeholder="что верно, что исправить" /></td>
                </tr>
              ))}
              <tr className="total-row">
                <td colSpan={2}><b>Итог</b></td>
                <td>{score(detail.assessment.aiTotalScore)}</td>
                <td><b>{anyScore ? score(previewTotal()) : score(detail.assessment.totalScore)}</b></td>
                <td><input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="общий комментарий" /></td>
              </tr>
            </tbody>
          </table>
          <div className="dialog-actions">
            <button className="secondary" disabled={working || anyScore} onClick={confirmAi}>Подтвердить оценку ИИ</button>
            <button className="primary" disabled={working || invalid} onClick={assess}>Сохранить оценку</button>
          </div>
        </>
      ) : (
        <p className="muted">Обучающийся ещё не завершил занятие — оценить можно после завершения.</p>
      )}

      <div className="panel-head"><b>Ответы обучающегося</b></div>
      {detail.drafts.length === 0 && detail.cards.length === 0 && <p className="muted">Ответов пока нет</p>}
      {detail.drafts.map((d) => (
        <div key={d.id} className="review-card">
          <b>{d.scenarioTitle ?? "Карточка"} · № {displayCardNumber(d.number)} · {d.state === "SAVED" ? `сохранена ${dateTime(d.savedAt)}` : "не сохранена"}</b>
          <p><em>Вводная:</em> {d.callerText}</p>
          <p><em>Адрес:</em> {fmtAddress(d.address)}</p>
          <p><em>Типы:</em> {d.incidentTypeIds.map(typeLabel).join(", ") || "—"} · <em>Службы:</em> {d.services.map((s) => s.label).join(", ") || "—"}</p>
          <p><em>Описание:</em> {d.description || "—"}</p>
        </div>
      ))}
      {detail.cards.map((c) => (
        <div key={c.id} className="review-card">
          <b>{c.scenarioTitle ?? "Карточка"} · № {displayCardNumber(c.number)} · {statusLabels[c.status] ?? c.status}{c.sla.acceptanceOverdue ? " · просрочено принятие" : ""}{c.sla.processingOverdue ? " · просрочена отработка" : ""}</b>
          <p><em>{c.incidentType.label}</em> · {c.address.raw}</p>
          <ul className="review-timeline">
            {c.timeline.map((t) => <li key={t.id}><time>{dateTime(t.occurredAt)}</time> <span>{t.actorLabel}</span> <b>{actionLabels[t.action] ?? t.action}</b> {t.comment && <i>❯ {t.comment}</i>}</li>)}
          </ul>
          {c.outboundCalls.length > 0 && <p><em>Звонки:</em> {c.outboundCalls.map((call) => `${call.target.shortNumber} (${call.state})`).join(", ")}</p>}
        </div>
      ))}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}
