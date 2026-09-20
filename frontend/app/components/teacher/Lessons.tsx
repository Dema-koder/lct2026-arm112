"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type CardSource, type Group, type Lesson, type LessonKind, type LessonMode, type LessonMonitor, type LessonReport, type ScenarioListItem, type SessionDetail } from "../../../lib/api";
import { actionLabels, categoryLabels, dateTime, kindLabels, lessonStateLabels, mmss, modeLabels, score, sessionStateLabels, sourceLabels, statusLabels } from "../../../lib/format";
import { ErrorBanner, Notice, useAction, useClock, useNotice, useSocket } from "../common";
import { AssessmentView } from "../trainee/Results";

/** Занятия преподавателя: список, создание, монитор, отчёт, оценка. */
export function Lessons({ token }: { token: string }) {
  const [lessons, setLessons] = useState<Lesson[]>([]);
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
        <button className="primary-button" onClick={() => setCreating(true)}>Новое занятие</button>
      </div>
      <table className="data-table">
        <thead>
          <tr><th>Создано</th><th>Название</th><th>Группа</th><th>Вид</th><th>Режим</th><th>Участников</th><th>Состояние</th></tr>
        </thead>
        <tbody>
          {lessons.length === 0 && <tr><td colSpan={7} className="muted">Занятий ещё нет — создайте первое</td></tr>}
          {lessons.map((l) => (
            <tr key={l.id} className="clickable" onClick={() => setSelected(l.id)}>
              <td>{dateTime(l.createdAt)}</td>
              <td><b>{l.title}</b></td>
              <td>{l.groupName ?? "—"}</td>
              <td>{kindLabels[l.kind]}</td>
              <td>{modeLabels[l.mode]}</td>
              <td>{l.sessionCount}</td>
              <td><span className={`state-pill ${l.state.toLowerCase()}`}>{lessonStateLabels[l.state]}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
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
  const [category, setCategory] = useState("");
  const [chosenScenarios, setChosenScenarios] = useState<string[]>([]);
  const [chosenTrainees, setChosenTrainees] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [working, run] = useAction(setError);

  useEffect(() => {
    api.teacher.groups(token).then((g) => { setGroups(g); if (g[0]) setGroupId(g[0].id); }).catch((e) => setError(e.message));
  }, [token]);
  useEffect(() => {
    const source = cardSource === "MIXED" ? undefined : cardSource === "TRAINEE_MADE" ? "TRAINEE_MADE" : undefined;
    api.teacher.scenarios(token, { category: category || undefined, source }).then((list) => {
      setScenarios(cardSource === "GENERATED" ? list.filter((s) => s.source !== "TRAINEE_MADE") : list);
    }).catch((e) => setError(e.message));
  }, [token, category, cardSource]);

  const group = groups.find((g) => g.id === groupId);
  const members = group?.members ?? [];
  const toggle = (list: string[], id: string, set: (v: string[]) => void) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

  const submit = () => run(async () => {
    const lesson = await api.teacher.createLesson(token, {
      title: title.trim() || `${kindLabels[kind]} · ${modeLabels[mode]}`,
      groupId: groupId || null, kind, mode, cardSource,
      scenarioIds: chosenScenarios, traineeIds: chosenTrainees,
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
          </select></label>
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
        <small>выбрано: {chosenScenarios.length}</small>
      </div>
      <div className="check-list">
        {scenarios.map((s) => (
          <label key={s.id} className={chosenScenarios.includes(s.id) ? "on" : ""}>
            <input type="checkbox" checked={chosenScenarios.includes(s.id)} onChange={() => toggle(chosenScenarios, s.id, setChosenScenarios)} />
            <b>{s.id}</b> <em>{categoryLabels[s.category] ?? s.category} · сложность {s.difficulty}{s.referenceConfirmed ? " · эталон ✓" : ""}</em>
            <span>{s.callerText}</span>
          </label>
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
        <span className="muted">{kindLabels[lesson.kind]} · {modeLabels[lesson.mode]} · {lesson.groupName ?? "без группы"} · <span className={`state-pill ${lesson.state.toLowerCase()}`}>{lessonStateLabels[lesson.state]}</span></span>
        <span className="spacer" />
        {lesson.state === "DRAFT" && <button className="primary-button" disabled={working} onClick={start}>Начать</button>}
        {lesson.state === "ACTIVE" && <button className="danger-button" disabled={working} onClick={complete}>Завершить занятие</button>}
        {lesson.state === "COMPLETED" && lesson.kind === "EXAM" && !lesson.resultsPublishedAt && (
          <button className="primary-button" disabled={working || !allAssessed} title={allAssessed ? undefined : "Сначала оцените всех участников"} onClick={publish}>Опубликовать результаты</button>
        )}
        {lesson.resultsPublishedAt && <small className="muted">опубликовано {dateTime(lesson.resultsPublishedAt)}</small>}
      </div>

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

      {report && report.rows.some((r) => r.finalTotal !== null) && (
        <>
          <div className="panel-head">
            <b>Отчёт</b>
            <small className="muted">рейтинг занятия {score(report.groupRating)}</small>
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

/** Две простые диаграммы inline-SVG: распределение итогов и время vs норматив. Без библиотек. */
function ReportCharts({ report }: { report: LessonReport }) {
  const rows = report.rows.filter((r) => r.finalTotal !== null);
  if (rows.length === 0) return null;
  const width = 420, height = 140, pad = 24;
  const bar = Math.max(8, Math.min(40, (width - pad * 2) / rows.length - 6));
  return (
    <div className="charts">
      <figure>
        <figcaption>Итоговые баллы</figcaption>
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Итоговые баллы по обучающимся">
          <line x1={pad} y1={height - pad} x2={width - pad / 2} y2={height - pad} stroke="#8e9ca3" />
          {rows.map((r, i) => {
            const h = ((r.finalTotal ?? 0) / 100) * (height - pad * 2);
            const x = pad + i * ((width - pad * 2) / rows.length);
            return (
              <g key={r.sessionId}>
                <rect x={x} y={height - pad - h} width={bar} height={h} fill={r.teacherTotal !== null ? "#0784c6" : "#5a6a72"} />
                <text x={x + bar / 2} y={height - pad + 12} fontSize="9" textAnchor="middle">{r.workstationNumber ?? "·"}</text>
                <text x={x + bar / 2} y={height - pad - h - 3} fontSize="9" textAnchor="middle">{Math.round(r.finalTotal ?? 0)}</text>
              </g>
            );
          })}
        </svg>
      </figure>
      <figure>
        <figcaption>Оценка по времени</figcaption>
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Оценка по времени">
          <line x1={pad} y1={height - pad} x2={width - pad / 2} y2={height - pad} stroke="#8e9ca3" />
          {rows.map((r, i) => {
            const h = ((r.timingScore ?? 0) / 100) * (height - pad * 2);
            const x = pad + i * ((width - pad * 2) / rows.length);
            return (
              <g key={r.sessionId}>
                <rect x={x} y={height - pad - h} width={bar} height={h} fill={(r.timingScore ?? 0) < 100 ? "#b03f2e" : "#2f9a5a"} />
                <text x={x + bar / 2} y={height - pad + 12} fontSize="9" textAnchor="middle">{r.workstationNumber ?? "·"}</text>
              </g>
            );
          })}
        </svg>
      </figure>
    </div>
  );
}

function SessionReview({ token, sessionId, onBack, lessonKind }: { token: string; sessionId: string; onBack: () => void; lessonKind: LessonKind }) {
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [total, setTotal] = useState("");
  const [comment, setComment] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const d = await api.teacher.session(token, sessionId);
    setDetail(d);
    if (d.assessment?.teacherTotalScore !== null && d.assessment?.teacherTotalScore !== undefined) setTotal(String(d.assessment.teacherTotalScore));
    if (d.assessment?.teacherComment) setComment(d.assessment.teacherComment);
  }, [token, sessionId]);
  useEffect(() => { void run(load); }, [load, run]);

  if (!detail) return <div className="boot-screen">Загрузка…</div>;
  const assess = () => run(async () => {
    await api.teacher.assess(token, sessionId, Number(total), comment);
    setNotice("Оценка сохранена — она стала итоговой");
    await load();
  });

  return (
    <section className="panel-page">
      <div className="panel-head">
        <button className="ghost" onClick={onBack}>‹ к занятию</button>
        <b>{detail.trainee?.displayName ?? "Обучающийся"}</b>
        <span className="muted">АРМ {detail.trainee?.workstationNumber ?? "—"} · {detail.session.lessonTitle} · {kindLabels[lessonKind]}</span>
      </div>

      {detail.drafts.map((d) => (
        <div key={d.id} className="review-card">
          <b>Карточка {d.number.replace(/\D/g, "").slice(-8)} · {d.state === "SAVED" ? `сохранена ${dateTime(d.savedAt)}` : "не сохранена"}</b>
          <p><em>Вводная:</em> {d.callerText}</p>
          <p><em>Адрес:</em> {[d.address.locality, d.address.street, d.address.house && `д. ${d.address.house}`, d.address.building && `к. ${d.address.building}`, d.address.apartment && `кв. ${d.address.apartment}`].filter(Boolean).join(", ") || "—"}{d.address.descriptive ? ` (${d.address.descriptive})` : ""}</p>
          <p><em>Типы:</em> {d.incidentTypeIds.join(", ") || "—"} · <em>Службы:</em> {d.services.map((s) => s.label).join(", ") || "—"}</p>
          <p><em>Описание:</em> {d.description || "—"}</p>
        </div>
      ))}
      {detail.cards.map((c) => (
        <div key={c.id} className="review-card">
          <b>Карточка {c.number.replace(/\D/g, "").slice(-8)} · {statusLabels[c.status] ?? c.status}{c.sla.acceptanceOverdue ? " · просрочено принятие" : ""}{c.sla.processingOverdue ? " · просрочена отработка" : ""}</b>
          <p><em>{c.incidentType.label}</em> · {c.address.raw}</p>
          <ul className="review-timeline">
            {c.timeline.map((t) => <li key={t.id}><time>{dateTime(t.occurredAt)}</time> <span>{t.actorLabel}</span> <b>{actionLabels[t.action] ?? t.action}</b> {t.comment && <i>❯ {t.comment}</i>}</li>)}
          </ul>
          {c.outboundCalls.length > 0 && <p><em>Звонки:</em> {c.outboundCalls.map((call) => `${call.target.shortNumber} (${call.state})`).join(", ")}</p>}
        </div>
      ))}

      {detail.assessment ? (
        <>
          <AssessmentView assessment={detail.assessment} title="Оценка системы и итог" />
          <div className="form-grid teacher-assess">
            <label><span>Оценка преподавателя (0–100)</span><input type="number" min={0} max={100} step={0.5} value={total} onChange={(e) => setTotal(e.target.value)} /></label>
            <label className="wide"><span>Комментарий обучающемуся</span><input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="что было верно, что исправить" /></label>
            <div className="dialog-actions"><button className="primary" disabled={working || total === "" || Number(total) < 0 || Number(total) > 100} onClick={assess}>Сохранить оценку</button></div>
          </div>
        </>
      ) : (
        <p className="muted">Обучающийся ещё не завершил занятие — оценить можно после завершения.</p>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}
