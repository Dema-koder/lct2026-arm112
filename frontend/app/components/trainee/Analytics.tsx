"use client";

import { useEffect, useMemo, useState } from "react";
import { api, type Assessment, type Rating, type ResultItem } from "../../../lib/api";
import { getMessage, kindLabels, modeLabels, score } from "../../../lib/format";
import { avg, BarChart, Donut, HorizontalBars, KpiGrid, Sparkline } from "../analytics/charts";

/**
 * Личная аналитика обучающегося: динамика баллов, слабые критерии,
 * типичные ошибки и рекомендации — только свои данные.
 */
export function TraineeAnalytics({ token, refreshKey }: { token: string; refreshKey: number }) {
  const [items, setItems] = useState<ResultItem[]>([]);
  const [rating, setRating] = useState<Rating | null>(null);
  const [assessments, setAssessments] = useState<Assessment[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [results, ratingValue] = await Promise.all([api.results(token), api.rating(token)]);
        if (cancelled) return;
        setItems(results);
        setRating(ratingValue);
        const visible = results.filter((r) => r.visible && r.assessmentId).slice(0, 20);
        const loaded = await Promise.all(
          visible.map((r) => api.assessment(token, r.assessmentId!).catch(() => null)),
        );
        if (cancelled) return;
        setAssessments(loaded.filter((a): a is Assessment => a !== null));
        setError("");
      } catch (err) {
        if (!cancelled) setError(getMessage(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [token, refreshKey]);

  const visible = useMemo(() => items.filter((i) => i.visible && i.finalTotal !== null), [items]);
  const pending = items.filter((i) => !i.visible).length;
  const history = useMemo(
    () => [...visible].reverse().map((i) => ({ ...i, total: i.finalTotal! })),
    [visible],
  );
  const spark = history.map((h) => h.total);
  const mean = avg(visible.map((v) => v.finalTotal));
  const last = visible[0]?.finalTotal ?? null;
  const prev = visible[1]?.finalTotal ?? null;
  const delta = last !== null && prev !== null ? last - prev : null;

  const byMode = useMemo(() => {
    const fill = visible.filter((v) => v.mode === "CARD_FILL");
    const actions = visible.filter((v) => v.mode === "CARD_ACTIONS");
    return [
      { key: "fill", label: "Заполнение", value: avg(fill.map((v) => v.finalTotal)) ?? 0, color: "#0784c6", hint: `${fill.length} занятий` },
      { key: "actions", label: "ДДС", value: avg(actions.map((v) => v.finalTotal)) ?? 0, color: "#5a6a72", hint: `${actions.length} занятий` },
    ].filter((x) => x.hint !== "0 занятий");
  }, [visible]);

  const byKind = useMemo(() => {
    const groups: Record<string, number[]> = {};
    for (const v of visible) {
      (groups[v.lessonKind] ??= []).push(v.finalTotal!);
    }
    return Object.entries(groups).map(([kind, scores]) => ({
      key: kind,
      label: kindLabels[kind] ?? kind,
      value: avg(scores) ?? 0,
      color: kind === "EXAM" ? "#0c6fa4" : kind === "CHECK" ? "#2f9a5a" : "#8e9ca3",
    }));
  }, [visible]);

  const criteria = useMemo(() => {
    const buckets: Record<string, number[]> = {};
    for (const a of assessments) {
      const push = (code: string, value: number | null) => {
        if (value === null) return;
        (buckets[code] ??= []).push(value);
      };
      push("timing", a.timingScore);
      push("language", a.languageScore);
      if (a.mode === "CARD_FILL") {
        push("address", a.addressScore);
        push("classification", a.classificationScore);
        push("services", a.servicesScore);
      } else {
        push("actions", a.actionsScore);
        push("communication", a.communicationScore);
      }
    }
    const labels: Record<string, string> = {
      timing: "Время", language: "Грамотность", address: "Адрес",
      classification: "Тип", services: "Службы", actions: "Действия", communication: "Коммуникация",
    };
    return Object.entries(buckets)
      .map(([code, vals]) => ({
        key: code,
        label: labels[code] ?? code,
        value: avg(vals) ?? 0,
        color: (avg(vals) ?? 0) < 70 ? "#b03f2e" : (avg(vals) ?? 0) < 85 ? "#c47b1a" : "#2f9a5a",
      }))
      .sort((a, b) => a.value - b.value);
  }, [assessments]);

  const issueStats = useMemo(() => {
    const counts: Record<string, number> = { CRITICAL: 0, WARNING: 0, INFO: 0 };
    const top: Record<string, number> = {};
    for (const a of assessments) {
      for (const issue of a.issues) {
        counts[issue.severity] = (counts[issue.severity] ?? 0) + 1;
        top[issue.message] = (top[issue.message] ?? 0) + 1;
      }
    }
    const frequent = Object.entries(top)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([label, value], i) => ({
        key: String(i),
        label: label.length > 48 ? `${label.slice(0, 48)}…` : label,
        value,
        color: "#b03f2e",
        hint: label,
      }));
    return { counts, frequent };
  }, [assessments]);

  const recommendations = useMemo(() => {
    const map: Record<string, number> = {};
    for (const a of assessments) {
      for (const r of a.recommendations) map[r] = (map[r] ?? 0) + 1;
    }
    return Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 5);
  }, [assessments]);

  if (loading) return <div className="boot-screen">Сбор аналитики…</div>;

  return (
    <section className="panel-page analytics-page">
      <div className="panel-head">
        <b>Моя аналитика</b>
        <small className="muted">только ваши результаты · без данных других обучающихся</small>
      </div>
      {error && <p className="inline-error">{error}</p>}

      <KpiGrid items={[
        {
          label: "Средний балл",
          value: score(mean),
          hint: "По видимым завершённым занятиям",
          tone: mean !== null && mean >= 80 ? "ok" : mean !== null && mean < 60 ? "warn" : "neutral",
        },
        {
          label: "Место в группе",
          value: rating?.rank && rating.groupSize ? `${rating.rank} / ${rating.groupSize}` : "—",
          hint: rating ? `Рейтинг ${score(rating.value)}` : undefined,
        },
        {
          label: "Занятий",
          value: String(rating?.completedSessions ?? visible.length),
          hint: pending > 0 ? `ещё ${pending} на проверке` : "все результаты доступны",
        },
        {
          label: "Динамика",
          value: delta === null ? "—" : `${delta >= 0 ? "+" : ""}${Math.round(delta)}`,
          hint: "Сравнение последнего и предыдущего итога",
          tone: delta !== null && delta >= 0 ? "ok" : delta !== null ? "warn" : "neutral",
        },
      ]} />

      <div className="analytics-grid">
        <article className="analytics-card">
          <header>
            <b>Динамика оценок</b>
            <Sparkline values={spark} width={140} height={32} />
          </header>
          <BarChart
            ariaLabel="Итоги по занятиям"
            items={history.slice(-12).map((h, i) => ({
              key: h.sessionId,
              label: String(i + 1),
              value: h.total,
              color: h.source === "TEACHER" ? "#0784c6" : "#5a6a72",
              hint: `${h.lessonTitle} · ${modeLabels[h.mode]} · ${kindLabels[h.lessonKind]}`,
            }))}
          />
          <p className="chart-note">Синий — оценка преподавателя, серый — оценка системы</p>
        </article>

        <article className="analytics-card">
          <header><b>По режимам и видам</b></header>
          <HorizontalBars items={byMode} />
          {byKind.length > 0 && (
            <>
              <p className="chart-note">Средний балл по виду занятия</p>
              <HorizontalBars items={byKind} />
            </>
          )}
        </article>

        <article className="analytics-card">
          <header><b>Слабые места (критерии)</b></header>
          {criteria.length === 0
            ? <p className="muted chart-empty">Откройте завершённые занятия — критерии появятся после оценок</p>
            : <HorizontalBars items={criteria} />}
          <p className="chart-note">Чем левее / ниже полоса — тем больше внимания этому навыку</p>
        </article>

        <article className="analytics-card">
          <header><b>Типичные замечания</b></header>
          <Donut
            center={String(issueStats.counts.CRITICAL + issueStats.counts.WARNING + issueStats.counts.INFO)}
            segments={[
              { key: "c", value: issueStats.counts.CRITICAL, color: "#b03f2e", label: "Критичные" },
              { key: "w", value: issueStats.counts.WARNING, color: "#c47b1a", label: "Предупреждения" },
              { key: "i", value: issueStats.counts.INFO, color: "#8e9ca3", label: "Инфо" },
            ]}
          />
          {issueStats.frequent.length > 0 && (
            <>
              <p className="chart-note">Чаще всего</p>
              <HorizontalBars items={issueStats.frequent} max={Math.max(1, ...issueStats.frequent.map((f) => f.value))} />
            </>
          )}
        </article>
      </div>

      {recommendations.length > 0 && (
        <article className="analytics-card wide">
          <header><b>Рекомендации системы</b></header>
          <ul className="recommendations">
            {recommendations.map(([text, count]) => (
              <li key={text}>{text}{count > 1 ? <small className="muted"> · ×{count}</small> : null}</li>
            ))}
          </ul>
        </article>
      )}

      {visible.length === 0 && !error && (
        <p className="muted">Пока нет завершённых видимых результатов — пройдите занятие, и здесь появится динамика.</p>
      )}
    </section>
  );
}
