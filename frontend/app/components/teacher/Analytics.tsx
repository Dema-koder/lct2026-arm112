"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type Group, type Lesson, type LessonReport, type ReportRow } from "../../../lib/api";
import { getMessage, kindLabels, lessonStateLabels, modeLabels, score } from "../../../lib/format";
import { avg, Donut, HorizontalBars, KpiGrid } from "../analytics/charts";
import { CsvExportButton } from "../common";

type LessonBundle = { lesson: Lesson; report: LessonReport | null };
type AnalyticRow = ReportRow & {
  lessonId: string;
  lessonTitle: string;
  kind: string;
  mode: string;
  groupId: string | null;
  groupName: string | null;
};

/**
 * Аналитика преподавателя: сводка по всем своим занятиям и группам —
 * нагрузка, средние баллы, расхождения ИИ/преподавателя, проблемные места.
 * Фильтры занятия / группы / обучающегося комбинируются (AND).
 */
export function TeacherAnalytics({ token }: { token: string }) {
  const [bundles, setBundles] = useState<LessonBundle[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [focusLessonId, setFocusLessonId] = useState("");
  const [focusGroupId, setFocusGroupId] = useState("");
  const [focusTraineeId, setFocusTraineeId] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [lessons, groupList] = await Promise.all([
        api.teacher.lessons(token),
        api.teacher.groups(token).catch(() => [] as Group[]),
      ]);
      setGroups(groupList);
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
    const rows: AnalyticRow[] = [];
    for (const b of bundles) {
      if (!b.report) continue;
      for (const r of b.report.rows) {
        rows.push({
          ...r,
          lessonId: b.lesson.id,
          lessonTitle: b.lesson.title,
          kind: b.lesson.kind,
          mode: b.lesson.mode,
          groupId: b.lesson.groupId,
          groupName: b.lesson.groupName,
        });
      }
    }
    return rows;
  }, [bundles]);

  const graded = useMemo(() => allRows.filter((r) => r.finalTotal !== null), [allRows]);

  const hasUngrouped = bundles.some((b) => !b.lesson.groupId);

  const groupOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const group of groups) map.set(group.id, group.name);
    for (const b of bundles) {
      if (b.lesson.groupId && b.lesson.groupName) map.set(b.lesson.groupId, b.lesson.groupName);
    }
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1], "ru"));
  }, [groups, bundles]);

  const lessonOptions = useMemo(() => {
    return bundles.filter((b) => {
      if (!focusGroupId) return true;
      if (focusGroupId === "none") return !b.lesson.groupId;
      return b.lesson.groupId === focusGroupId;
    });
  }, [bundles, focusGroupId]);

  const traineeOptions = useMemo(() => {
    const map = new Map<string, string>();
    for (const r of allRows) {
      if (focusGroupId === "none" && r.groupId) continue;
      if (focusGroupId && focusGroupId !== "none" && r.groupId !== focusGroupId) continue;
      if (focusLessonId && r.lessonId !== focusLessonId) continue;
      map.set(r.traineeId, r.traineeName);
    }
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1], "ru"));
  }, [allRows, focusGroupId, focusLessonId]);

  const matchLesson = useCallback((lesson: Lesson) => {
    if (focusGroupId === "none" && lesson.groupId) return false;
    if (focusGroupId && focusGroupId !== "none" && lesson.groupId !== focusGroupId) return false;
    if (focusLessonId && lesson.id !== focusLessonId) return false;
    return true;
  }, [focusGroupId, focusLessonId]);

  const focusRows = useMemo(() => {
    return graded.filter((r) => {
      if (focusGroupId === "none" && r.groupId) return false;
      if (focusGroupId && focusGroupId !== "none" && r.groupId !== focusGroupId) return false;
      if (focusLessonId && r.lessonId !== focusLessonId) return false;
      if (focusTraineeId && r.traineeId !== focusTraineeId) return false;
      return true;
    });
  }, [graded, focusGroupId, focusLessonId, focusTraineeId]);

  const filteredBundles = useMemo(
    () => bundles.filter((b) => matchLesson(b.lesson)),
    [bundles, matchLesson],
  );

  const filtersOn = focusLessonId !== "" || focusGroupId !== "" || focusTraineeId !== "";
  const clearFilters = () => {
    setFocusLessonId("");
    setFocusGroupId("");
    setFocusTraineeId("");
  };

  const filterHint = filtersOn ? "По выбранным фильтрам" : "По всем занятиям с оценками";

  const stateMix = useMemo(() => {
    const counts = { DRAFT: 0, ACTIVE: 0, COMPLETED: 0 };
    for (const b of filteredBundles) {
      counts[b.lesson.state as keyof typeof counts] = (counts[b.lesson.state as keyof typeof counts] ?? 0) + 1;
    }
    return [
      { key: "d", value: counts.DRAFT, color: "#8e9ca3", label: "Черновики" },
      { key: "a", value: counts.ACTIVE, color: "#2f9a5a", label: "Идут" },
      { key: "c", value: counts.COMPLETED, color: "#0784c6", label: "Завершены" },
    ];
  }, [filteredBundles]);

  const kindMix = useMemo(() => {
    const counts = { TRAINING: 0, CHECK: 0, EXAM: 0 };
    for (const b of filteredBundles) {
      counts[b.lesson.kind as keyof typeof counts] = (counts[b.lesson.kind as keyof typeof counts] ?? 0) + 1;
    }
    return [
      { key: "t", value: counts.TRAINING, color: "#5a6a72", label: kindLabels.TRAINING },
      { key: "c", value: counts.CHECK, color: "#0c6fa4", label: kindLabels.CHECK },
      { key: "e", value: counts.EXAM, color: "#b07a1a", label: kindLabels.EXAM },
    ];
  }, [filteredBundles]);

  const meanFinal = avg(focusRows.map((r) => r.finalTotal));
  const meanTiming = avg(focusRows.map((r) => r.timingScore));
  const needsTeacher = focusRows.filter((r) => r.aiTotal !== null && r.teacherTotal === null).length;

  const traineeRanks = useMemo(() => {
    const map = new Map<string, { name: string; arm: string; scores: number[] }>();
    for (const r of focusRows) {
      const cur = map.get(r.traineeId) ?? { name: r.traineeName, arm: r.workstationNumber ?? "—", scores: [] };
      cur.scores.push(r.finalTotal!);
      map.set(r.traineeId, cur);
    }
    const ranked = [...map.entries()]
      .map(([id, v]) => ({
        key: id,
        label: `АРМ ${v.arm}`,
        value: avg(v.scores) ?? 0,
        hint: `${v.name} · ${v.scores.length} зан.`,
      }))
      .sort((a, b) => a.value - b.value);
    // порог 70: ниже — худшие, от 70 — лучшие (до 5 в каждой группе)
    const below = ranked.filter((item) => item.value < 70);
    const above = ranked.filter((item) => item.value >= 70);
    const worst = below.slice(0, 5).map((item) => ({ ...item, color: "#b03f2e" }));
    const best = (above.length <= 5 ? above : above.slice(-5))
      .map((item) => ({ ...item, color: "#2f9a5a" }));
    return { worst, best };
  }, [focusRows]);

  const lessonRanks = useMemo(() => {
    const ranked = filteredBundles
      .filter((b) => b.report && b.report.rows.some((r) => r.finalTotal !== null
        && (!focusTraineeId || r.traineeId === focusTraineeId)))
      .map((b) => {
        const scores = b.report!.rows
          .filter((r) => r.finalTotal !== null && (!focusTraineeId || r.traineeId === focusTraineeId))
          .map((r) => r.finalTotal!);
        const base = {
          key: b.lesson.id,
          label: b.lesson.title,
          value: avg(scores) ?? 0,
          hint: `${b.lesson.title} · ${kindLabels[b.lesson.kind]} · ${modeLabels[b.lesson.mode]} · ${lessonStateLabels[b.lesson.state]}`,
        };
        return base;
      })
      .sort((a, b) => a.value - b.value);
    const worst = ranked.slice(0, 5).map((item) => ({ ...item, color: "#b03f2e" }));
    const worstKeys = new Set(worst.map((item) => item.key));
    const best = ranked.length <= 5
      ? []
      : ranked.slice(-5)
        .filter((item) => !worstKeys.has(item.key))
        .map((item) => ({ ...item, color: "#2f9a5a" }));
    return { worst, best };
  }, [filteredBundles, focusTraineeId]);

  if (loading) return <div className="boot-screen">Сбор аналитики по занятиям…</div>;

  return (
    <section className="panel-page analytics-page">
      <div className="panel-head">
        <b>Аналитика занятий</b>
        <span className="spacer" />
        <button className="ghost" onClick={() => void load()}>обновить</button>
      </div>
      <div className="panel-head lesson-filters">
        <select value={focusLessonId} onChange={(e) => { setFocusLessonId(e.target.value); setFocusTraineeId(""); }} aria-label="Фильтр по занятию">
          <option value="">все занятия</option>
          {lessonOptions.map((b) => (
            <option key={b.lesson.id} value={b.lesson.id}>{b.lesson.title}</option>
          ))}
        </select>
        <select value={focusGroupId} onChange={(e) => { setFocusGroupId(e.target.value); setFocusLessonId(""); setFocusTraineeId(""); }} aria-label="Фильтр по группе">
          <option value="">все группы</option>
          {groupOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          {hasUngrouped && <option value="none">без группы</option>}
        </select>
        <select value={focusTraineeId} onChange={(e) => setFocusTraineeId(e.target.value)} aria-label="Фильтр по обучающемуся">
          <option value="">все обучающиеся</option>
          {traineeOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
        {filtersOn && <button type="button" className="ghost" onClick={clearFilters}>сбросить</button>}
        <small className="muted">{focusRows.length} из {graded.length}</small>
      </div>
      {error && <p className="inline-error">{error}</p>}

      <KpiGrid items={[
        {
          label: "Средний балл",
          value: score(meanFinal),
          hint: filterHint,
          tone: meanFinal !== null && meanFinal >= 80 ? "ok" : meanFinal !== null && meanFinal < 60 ? "warn" : "neutral",
        },
        {
          label: "Средний балл за время",
          value: score(meanTiming),
          hint: "По критерию «Время» в оценке",
          tone: meanTiming !== null && meanTiming < 80 ? "warn" : "ok",
        },
        {
          label: "Ждут проверки",
          value: String(needsTeacher),
          hint: "Есть оценка системы, нет вашей",
          tone: needsTeacher > 0 ? "warn" : "ok",
        },
      ]} />

      <div className="analytics-grid">
        <article className="analytics-card wide">
          <header><b>Портфель занятий</b></header>
          <div className="chart-split">
            <div className="chart-split-block">
              <p className="chart-subhead">По состоянию</p>
              <Donut center={String(filteredBundles.length)} segments={stateMix} size={220} />
            </div>
            <div className="chart-split-block">
              <p className="chart-subhead">По виду</p>
              <Donut center={String(filteredBundles.length)} segments={kindMix} size={220} />
            </div>
          </div>
          <p className="chart-note">Доли по количеству занятий (не баллы)</p>
        </article>

        <article className="analytics-card wide">
          <header><b>Средний балл по занятиям</b></header>
          {lessonRanks.worst.length === 0 && lessonRanks.best.length === 0
            ? <p className="muted chart-empty">Нет оценённых занятий</p>
            : (
              <div className="chart-split">
                {lessonRanks.worst.length > 0 && (
                  <div className="chart-split-block">
                    <p className="chart-subhead">5 худших</p>
                    <HorizontalBars items={lessonRanks.worst} scale="score" />
                  </div>
                )}
                {lessonRanks.best.length > 0 && (
                  <div className="chart-split-block">
                    <p className="chart-subhead">5 лучших</p>
                    <HorizontalBars items={lessonRanks.best} scale="score" />
                  </div>
                )}
              </div>
            )}
          <p className="chart-note">Шкала 0–100 · красный — слабые, зелёный — сильные</p>
        </article>

        {!focusTraineeId && (
          <article className="analytics-card wide">
            <header><b>Обучающиеся</b></header>
            {traineeRanks.worst.length === 0 && traineeRanks.best.length === 0
              ? <p className="muted chart-empty">Нет оценённых сессий</p>
              : (
                <div className="chart-split">
                  {traineeRanks.worst.length > 0 && (
                    <div className="chart-split-block">
                      <p className="chart-subhead">5 худших</p>
                      <HorizontalBars items={traineeRanks.worst} scale="score" />
                    </div>
                  )}
                  {traineeRanks.best.length > 0 && (
                    <div className="chart-split-block">
                      <p className="chart-subhead">5 лучших</p>
                      <HorizontalBars items={traineeRanks.best} scale="score" />
                    </div>
                  )}
                </div>
              )}
            <p className="chart-note">Средний балл 0–100 · от 70 — лучшие, ниже 70 — худшие · до 5 в каждой группе</p>
          </article>
        )}
      </div>

      <article className="analytics-card wide">
        <header>
          <b>Сводка по сессиям</b><small className="muted">{focusRows.length} из {graded.length}</small><span className="spacer" />
          <CsvExportButton fileName="аналитика-сессий.csv"
            headers={["Занятие", "Обучающийся", "АРМ", "Вид", "Режим", "Группа", "Время", "Синтаксические ошибки", "Оценка ИИ", "Оценка преподавателя", "Итог"]}
            rows={focusRows.map((r) => [r.lessonTitle, r.traineeName, r.workstationNumber ?? "", kindLabels[r.kind] ?? r.kind,
              modeLabels[r.mode] ?? r.mode, r.groupName ?? "", score(r.timingScore), r.syntaxErrors ?? "", score(r.aiTotal), score(r.teacherTotal), score(r.finalTotal)])} />
        </header>
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
