"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type Lesson, type LessonReport, type ReportRow } from "../../../lib/api";
import { getMessage, kindLabels, lessonStateLabels, modeLabels, score } from "../../../lib/format";
import { avg, BarChart, Donut, HorizontalBars, KpiGrid } from "../analytics/charts";

type LessonBundle = { lesson: Lesson; report: LessonReport | null };

/**
 * Аналитика преподавателя: сводка по всем своим занятиям и группам —
 * нагрузка, средние баллы, расхождения ИИ/преподавателя, проблемные места.
 */
export function TeacherAnalytics({ token }: { token: string }) {
  const [bundles, setBundles] = useState<LessonBundle[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [focusLessonId, setFocusLessonId] = useState<string>("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const lessons = await api.teacher.lessons(token);
      const recent = lessons.slice(0, 30);
      const withReports = await Promise.all(recent.map(async (lesson) => {
        if (lesson.state === "DRAFT") return { lesson, report: null };
        try {
          return { lesson, report: await api.teacher.report(token, lesson.id) };
        } catch {
          return { lesson, report: null };
        }
      }));
      setBundles(withReports);
      setError("");
    } catch (err) {
      setError(getMessage(err));
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  const allRows = useMemo(() => {
    const rows: Array<ReportRow & { lessonId: string; lessonTitle: string; kind: string; mode: string }> = [];
    for (const b of bundles) {
      if (!b.report) continue;
      for (const r of b.report.rows) {
        rows.push({
          ...r,
          lessonId: b.lesson.id,
          lessonTitle: b.lesson.title,
          kind: b.lesson.kind,
          mode: b.lesson.mode,
        });
      }
    }
    return rows;
  }, [bundles]);

  const graded = useMemo(() => allRows.filter((r) => r.finalTotal !== null), [allRows]);
  const focusRows = useMemo(() => focusLessonId
    ? graded.filter((r) => r.lessonId === focusLessonId)
    : graded, [focusLessonId, graded]);

  const stateMix = useMemo(() => {
    const counts = { DRAFT: 0, ACTIVE: 0, COMPLETED: 0 };
    for (const b of bundles) counts[b.lesson.state as keyof typeof counts] = (counts[b.lesson.state as keyof typeof counts] ?? 0) + 1;
    return [
      { key: "d", value: counts.DRAFT, color: "#8e9ca3", label: "Черновики" },
      { key: "a", value: counts.ACTIVE, color: "#2f9a5a", label: "Идут" },
      { key: "c", value: counts.COMPLETED, color: "#0784c6", label: "Завершены" },
    ];
  }, [bundles]);

  const meanFinal = avg(focusRows.map((r) => r.finalTotal));
  const meanTiming = avg(focusRows.map((r) => r.timingScore));
  const meanAi = avg(focusRows.map((r) => r.aiTotal));
  const meanTeacher = avg(focusRows.filter((r) => r.teacherTotal !== null).map((r) => r.teacherTotal));
  const needsTeacher = focusRows.filter((r) => r.aiTotal !== null && r.teacherTotal === null).length;
  const weakTiming = focusRows.filter((r) => (r.timingScore ?? 100) < 80).length;
  const syntaxHeavy = focusRows.filter((r) => (r.syntaxErrors ?? 0) >= 2).length;

  const byTrainee = useMemo(() => {
    const map = new Map<string, { name: string; arm: string; scores: number[] }>();
    for (const r of focusRows) {
      const cur = map.get(r.traineeId) ?? { name: r.traineeName, arm: r.workstationNumber ?? "—", scores: [] };
      cur.scores.push(r.finalTotal!);
      map.set(r.traineeId, cur);
    }
    return [...map.entries()]
      .map(([id, v]) => ({
        key: id,
        label: `АРМ ${v.arm}`,
        value: avg(v.scores) ?? 0,
        hint: `${v.name} · ${v.scores.length} зан.`,
        color: (avg(v.scores) ?? 0) < 70 ? "#b03f2e" : "#0784c6",
      }))
      .sort((a, b) => a.value - b.value)
      .slice(0, 12);
  }, [focusRows]);

  const byLesson = useMemo(() => {
    return bundles
      .filter((b) => b.report && b.report.rows.some((r) => r.finalTotal !== null))
      .map((b) => {
        const scores = b.report!.rows.map((r) => r.finalTotal).filter((v): v is number => v !== null);
        return {
          key: b.lesson.id,
          label: b.lesson.title,
          value: avg(scores) ?? 0,
          color: b.lesson.kind === "EXAM" ? "#0c6fa4" : "#5a6a72",
          hint: `${b.lesson.title} · ${kindLabels[b.lesson.kind]} · ${modeLabels[b.lesson.mode]} · ${lessonStateLabels[b.lesson.state]}`,
        };
      })
      .slice(0, 10);
  }, [bundles]);

  const aiVsTeacher = useMemo(() => {
    return focusRows
      .filter((r) => r.aiTotal !== null && r.teacherTotal !== null)
      .slice(0, 12)
      .map((r) => ({
        key: r.sessionId,
        label: r.workstationNumber ?? "·",
        value: Math.round((r.teacherTotal! - r.aiTotal!) * 10) / 10,
        color: (r.teacherTotal! - r.aiTotal!) >= 0 ? "#2f9a5a" : "#b03f2e",
        hint: `${r.traineeName}: ИИ ${score(r.aiTotal)} → преп. ${score(r.teacherTotal)}`,
      }));
  }, [focusRows]);

  if (loading) return <div className="boot-screen">Сбор аналитики по занятиям…</div>;

  return (
    <section className="panel-page analytics-page">
      <div className="panel-head">
        <b>Аналитика занятий</b>
        <select value={focusLessonId} onChange={(e) => setFocusLessonId(e.target.value)} aria-label="Фильтр по занятию">
          <option value="">все занятия</option>
          {bundles.map((b) => (
            <option key={b.lesson.id} value={b.lesson.id}>{b.lesson.title}</option>
          ))}
        </select>
        <span className="spacer" />
        <button className="ghost" onClick={() => void load()}>обновить</button>
      </div>
      {error && <p className="inline-error">{error}</p>}

      <KpiGrid items={[
        {
          label: "Средний итог",
          value: score(meanFinal),
          hint: focusLessonId ? "По выбранному занятию" : "По всем занятиям с оценками",
          tone: meanFinal !== null && meanFinal >= 80 ? "ok" : meanFinal !== null && meanFinal < 60 ? "warn" : "neutral",
        },
        {
          label: "Норматив времени",
          value: score(meanTiming),
          hint: `${weakTiming} сессий ниже 80 по времени`,
          tone: meanTiming !== null && meanTiming < 80 ? "warn" : "ok",
        },
        {
          label: "Ждут проверки",
          value: String(needsTeacher),
          hint: "Есть оценка системы, нет вашей",
          tone: needsTeacher > 0 ? "warn" : "ok",
        },
        {
          label: "Синт. ошибки ≥ 2",
          value: String(syntaxHeavy),
          hint: "Сессии с заметными замечаниями по тексту",
        },
      ]} />

      <div className="analytics-grid">
        <article className="analytics-card">
          <header><b>Портфель занятий</b></header>
          <Donut center={String(bundles.length)} segments={stateMix} size={280} />
          <p className="chart-note">Черновик / идёт / завершено</p>
        </article>

        <article className="analytics-card wide">
          <header><b>Средний балл по занятиям</b></header>
          {byLesson.length === 0
            ? <p className="muted chart-empty">Нет оценённых занятий</p>
            : <HorizontalBars items={byLesson} />}
        </article>

        <article className="analytics-card">
          <header><b>Обучающиеся (от слабых к сильным)</b></header>
          {byTrainee.length === 0
            ? <p className="muted chart-empty">Нет оценённых сессий</p>
            : <HorizontalBars items={byTrainee} />}
          <p className="chart-note">Подсказка при наведении — ФИО и число занятий</p>
        </article>

        <article className="analytics-card">
          <header><b>Коррекция оценки (преп. − ИИ)</b></header>
          {aiVsTeacher.length === 0
            ? <p className="muted chart-empty">Пока нет пар «оценка системы + ваша»</p>
            : (
              <BarChart
                ariaLabel="Разница оценки преподавателя и ИИ"
                max={Math.max(20, ...aiVsTeacher.map((x) => Math.abs(x.value)))}
                items={aiVsTeacher.map((x) => ({ ...x, value: Math.abs(x.value) }))}
              />
            )}
          <p className="chart-note">
            Зелёный в подсказке — вы повысили балл, красный — понизили.
            Среднее ИИ {score(meanAi)} · ваше {score(meanTeacher)}
          </p>
        </article>
      </div>

      <article className="analytics-card wide">
        <header><b>Сводка по сессиям</b><small className="muted">{focusRows.length} из {allRows.length}</small></header>
        <table className="data-table">
          <thead>
            <tr>
              <th>Занятие</th><th>Обучающийся</th><th>АРМ</th><th>Время</th>
              <th>Синт.</th><th>ИИ</th><th>Преп.</th><th>Итог</th>
            </tr>
          </thead>
          <tbody>
            {focusRows.length === 0 && (
              <tr><td colSpan={8} className="muted">Нет строк с итоговой оценкой</td></tr>
            )}
            {[...focusRows]
              .sort((a, b) => (a.finalTotal ?? 0) - (b.finalTotal ?? 0))
              .slice(0, 40)
              .map((r) => (
                <tr key={r.sessionId} className={(r.timingScore ?? 100) < 80 || (r.finalTotal ?? 100) < 60 ? "overdue" : ""}>
                  <td className="ellipsis" title={r.lessonTitle}>{r.lessonTitle}</td>
                  <td>{r.traineeName}</td>
                  <td>{r.workstationNumber ?? "—"}</td>
                  <td className="status-cell">{score(r.timingScore)}</td>
                  <td>{r.syntaxErrors ?? "—"}</td>
                  <td>{score(r.aiTotal)}</td>
                  <td>{score(r.teacherTotal)}</td>
                  <td><b>{score(r.finalTotal)}</b></td>
                </tr>
              ))}
          </tbody>
        </table>
      </article>
    </section>
  );
}
