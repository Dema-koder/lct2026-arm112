"use client";

import { useEffect, useMemo, useState } from "react";
import { api, type Assessment, type Rating, type ResultItem } from "../../../lib/api";
import { getMessage, dateOnly, kindLabels, modeLabels, score } from "../../../lib/format";
import { avg, BarChart, Donut, HorizontalBars, KpiGrid, Sparkline } from "../analytics/charts";

/**
 * Личная аналитика обучающегося: динамика баллов, слабые критерии,
 * типичные ошибки и рекомендации — только свои данные.
 * Разбор от языковой модели сюда не выводится: только то, что считается правилами по кодам замечаний.
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
  // динамика оценок — только занятия за последний месяц, по дате завершения
  const historyMonth = useMemo(() => {
    const from = new Date();
    from.setMonth(from.getMonth() - 1);
    return [...visible]
      .filter((i) => i.completedAt && new Date(i.completedAt) >= from)
      .sort((a, b) => new Date(a.completedAt!).getTime() - new Date(b.completedAt!).getTime())
      .map((i) => ({ ...i, total: i.finalTotal! }));
  }, [visible]);
  const spark = historyMonth.map((h) => h.total);
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
        label,
        value,
        color: "#b03f2e",
        hint: label,
      }));
    return { counts, frequent };
  }, [assessments]);

  // Что повторяется — по кодам замечаний, а не по тексту рекомендаций: текст можно
  // переформулировать, код — нет. Неоповещённая служба считается отдельно по каждой службе.
  const recurring = useMemo(() => {
    const map = new Map<string, { message: string; critical: boolean; lessons: number }>();
    for (const a of assessments) {
      const seen = new Set<string>();
      for (const issue of a.issues) {
        if (issue.severity === "INFO") continue;
        const key = issue.code === "SERVICE_MISSING" ? `${issue.code}:${String(issue.expected)}` : issue.code;
        if (seen.has(key)) continue;
        seen.add(key);
        const cur = map.get(key) ?? { message: issue.message, critical: issue.severity === "CRITICAL", lessons: 0 };
        cur.lessons += 1;
        map.set(key, cur);
      }
    }
    return [...map.entries()].sort((x, y) => y[1].lessons - x[1].lessons || Number(y[1].critical) - Number(x[1].critical)).slice(0, 6);
  }, [assessments]);

  // Текст рекомендации задан правилом на код замечания, меняется только хвост «(карточек: N)» —
  // без него одинаковые рекомендации складываются по занятиям.
  const recommendations = useMemo(() => {
    const map = new Map<string, number>();
    for (const a of assessments) {
      const seen = new Set<string>();
      for (const r of a.recommendations) {
        const text = r.replace(/\s*\(карточек: \d+\)$/, "");
        if (seen.has(text)) continue;
        seen.add(text);
        map.set(text, (map.get(text) ?? 0) + 1);
      }
    }
    return [...map.entries()].sort((x, y) => y[1] - x[1]).slice(0, 6);
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
          {historyMonth.length === 0
            ? <p className="muted chart-empty">За последний месяц оценок пока нет</p>
            : (
              <BarChart
                ariaLabel="Итоги по занятиям за последний месяц"
                scale="score"
                items={historyMonth.map((h) => {
                  const when = h.completedAt ? new Date(h.completedAt) : null;
                  const axis = when
                    ? new Intl.DateTimeFormat("ru-RU", { day: "2-digit", month: "2-digit" }).format(when)
                    : "—";
                  return {
                    key: h.sessionId,
                    label: axis,
                    value: h.total,
                    color: h.source === "TEACHER" ? "#0784c6" : "#5a6a72",
                    hint: `${dateOnly(h.completedAt)} · ${h.lessonTitle} · ${modeLabels[h.mode]} · ${kindLabels[h.lessonKind]} · ${Math.round(h.total)} из 100`,
                  };
                })}
              />
            )}
          <p className="chart-note">За последний месяц · шкала 0–100. Синий — преподаватель, серый — система</p>
        </article>

        <article className="analytics-card">
          <header><b>По режимам</b></header>
          {byMode.length === 0
            ? <p className="muted chart-empty">Нет оценённых занятий</p>
            : <HorizontalBars items={byMode} scale="score" />}
          <p className="chart-note">Средний балл по режимам занятий (0–100)</p>
        </article>

        <article className="analytics-card">
          <header><b>По видам</b></header>
          {byKind.length === 0
            ? <p className="muted chart-empty">Нет оценённых занятий</p>
            : <HorizontalBars items={byKind} scale="score" />}
          <p className="chart-note">Средний балл по видам занятий (0–100)</p>
        </article>

        <article className="analytics-card">
          <header><b>Слабые места (критерии)</b></header>
          {criteria.length === 0
            ? <p className="muted chart-empty">Откройте завершённые занятия — критерии появятся после оценок</p>
            : <HorizontalBars items={criteria} scale="score" />}
          <p className="chart-note">Шкала 0–100. Чем короче полоса — тем слабее критерий</p>
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
          <p className="chart-note">Круг — доли по тяжести (количество)</p>
          {issueStats.frequent.length > 0 && (
            <>
              <p className="chart-note">Чаще всего — сколько раз встретилось (не баллы)</p>
              <HorizontalBars items={issueStats.frequent} scale="count" unit="раз" />
            </>
          )}
        </article>
      </div>

      {recurring.length > 0 && (
        <article className="analytics-card wide">
          <header><b>Что повторяется</b><small className="muted">по видам ошибок, в скольких занятиях из {assessments.length}</small></header>
          <ul className="recommendations">
            {recurring.map(([key, item]) => (
              <li key={key}>{item.message}{item.critical && <span className="issue-tag">критично</span>}
                <small className="muted"> · в {item.lessons} из {assessments.length}</small></li>
            ))}
          </ul>
        </article>
      )}

      {recommendations.length > 0 && (
        <article className="analytics-card wide">
          <header><b>Рекомендации</b><small className="muted">по правилам, по видам ошибок, в скольких занятиях из {assessments.length}</small></header>
          <ul className="recommendations">
            {recommendations.map(([text, lessons]) => (
              <li key={text}>{text}<small className="muted"> · в {lessons} из {assessments.length}</small></li>
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
