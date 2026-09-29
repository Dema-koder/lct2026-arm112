"use client";

import { useEffect, useState } from "react";
import { api, type Assessment, type Debrief, type Material, type Rating, type ResultItem } from "../../../lib/api";
import { bytes, criterionLabels, dateTime, formatIssueValue, getMessage, kindLabels, modeLabels, score } from "../../../lib/format";
import { CsvExportButton } from "../common";

/** Мои результаты: список занятий по правилу видимости, детали оценки, рейтинг, материалы группы. */
export function Results({ token, refreshKey }: { token: string; refreshKey: number }) {
  const [items, setItems] = useState<ResultItem[]>([]);
  const [rating, setRating] = useState<Rating | null>(null);
  const [materials, setMaterials] = useState<Material[]>([]);
  const [selected, setSelected] = useState<Assessment | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.results(token), api.rating(token), api.traineeMaterials(token)])
      .then(([results, ratingValue, files]) => {
        if (cancelled) return;
        setItems(results);
        setRating(ratingValue);
        setMaterials(files);
      })
      .catch((err) => setError(getMessage(err)));
    return () => { cancelled = true; };
  }, [token, refreshKey]);

  const open = async (item: ResultItem) => {
    if (!item.assessmentId) return;
    try {
      setSelected(await api.assessment(token, item.assessmentId));
    } catch (err) {
      setError(getMessage(err));
    }
  };

  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Мои результаты</b>
        {rating && (
          <span className="rating-chip">
            Рейтинг <strong>{score(rating.value)}</strong>
            {rating.rank && rating.groupSize ? <> · место {rating.rank} из {rating.groupSize}</> : null}
            {" "}· занятий {rating.completedSessions ?? 0}
          </span>
        )}
        <span className="spacer" />
        <CsvExportButton
          fileName="мои-результаты.csv"
          headers={["Занятие", "Вид", "Режим", "Завершено", "Итог", "Оценил", "Состояние"]}
          rows={items.map((item) => [
            item.lessonTitle,
            kindLabels[item.lessonKind] ?? item.lessonKind,
            modeLabels[item.mode] ?? item.mode,
            dateTime(item.completedAt),
            item.visible ? score(item.finalTotal) : "",
            item.source === "TEACHER" ? "преподаватель" : item.source === "AI" ? "система" : "",
            item.visible ? "опубликовано" : "на проверке у преподавателя",
          ])}
        />
      </div>
      {error && <p className="inline-error">{error}</p>}
      <table className="data-table">
        <thead>
          <tr><th>Занятие</th><th>Вид</th><th>Режим</th><th>Завершено</th><th>Итог</th><th>Оценил</th></tr>
        </thead>
        <tbody>
          {items.length === 0 && <tr><td colSpan={6} className="muted">Завершённых занятий пока нет</td></tr>}
          {items.map((item) => (
            <tr key={item.sessionId} className={item.visible ? "clickable" : ""} onClick={() => item.visible && open(item)}>
              <td>{item.lessonTitle}</td>
              <td>{kindLabels[item.lessonKind]}</td>
              <td>{modeLabels[item.mode]}</td>
              <td>{dateTime(item.completedAt)}</td>
              <td>{item.visible ? <b>{score(item.finalTotal)}</b> : <span className="muted">на проверке у преподавателя</span>}</td>
              <td>{item.source === "TEACHER" ? "преподаватель" : item.source === "AI" ? "система" : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected && <AssessmentView assessment={selected} onClose={() => setSelected(null)} token={token} />}

      {materials.length > 0 && (
        <>
          <div className="panel-head"><b>Методические материалы</b></div>
          <ul className="material-list">
            {materials.map((m) => (
              <li key={m.id}>
                <a href={api.materialDownloadUrl(m.id)} onClick={(event) => { event.preventDefault(); void download(token, m); }}>{m.title}</a>
                <small>{m.fileName} · {bytes(m.sizeBytes)}</small>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

async function download(token: string, material: Material) {
  const response = await fetch(api.materialDownloadUrl(material.id), {
    headers: { Authorization: `Bearer ${token}`, "X-Contract-Version": "0.3" },
  });
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = material.fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * Разбор от языковой модели. Считается в фоне после оценки, поэтому блок сам ждёт готовности.
 * Рекомендации по правилам показаны выше и от модели не зависят.
 */
export function LlmDebrief({ token, assessmentId }: { token: string; assessmentId: string }) {
  const [debrief, setDebrief] = useState<Debrief | null>(null);
  useEffect(() => {
    let stopped = false;
    let timer = 0;
    const poll = async (attempt: number) => {
      try {
        const d = await api.debrief(token, assessmentId);
        if (stopped) return;
        setDebrief(d);
        // разбор на модели идёт около полутора минут на человека; ждём до 15 минут
        if (d.state === "PENDING" && attempt < 180) timer = window.setTimeout(() => void poll(attempt + 1), 5000);
      } catch {
        if (!stopped) setDebrief(null);
      }
    };
    void poll(0);
    return () => { stopped = true; window.clearTimeout(timer); };
  }, [token, assessmentId]);

  if (!debrief) return null;
  return (
    <div className={`llm-debrief ${debrief.state.toLowerCase()}`}>
      <p className="llm-debrief-title">Рекомендация от языковой модели</p>
      {debrief.state === "PENDING" && <p className="muted">Готовится — обычно одна-две минуты. Можно закрыть окно и вернуться позже.</p>}
      {debrief.state === "READY" && (
        <>
          <p className="llm-debrief-text">{debrief.text}</p>
          <small className="muted">Написано моделью по вашим ошибкам. Если что-то расходится с рекомендациями выше — ориентируйтесь на них и на преподавателя.</small>
        </>
      )}
      {debrief.state === "UNAVAILABLE" && <p className="muted">{debrief.text ?? "Разбор от модели не составлен."}</p>}
    </div>
  );
}

export function AssessmentView({ assessment, onClose, title, hideIssues, token }: { assessment: Assessment; onClose?: () => void; title?: string; hideIssues?: boolean; token?: string }) {
  const fill = assessment.mode === "CARD_FILL";
  return (
    <section className="assessment-card">
      <header>
        <b>{title ?? "Результат занятия"}</b>
        {onClose && <button onClick={onClose} aria-label="Закрыть">×</button>}
      </header>
      <div className="assessment-body">
        <div className="score-circle">
          {Math.round(assessment.totalScore ?? 0)}
          <small>{assessment.source === "TEACHER" ? <>оценка<br />преподавателя</> : "баллов"}</small>
        </div>
        <div className="score-grid">
          <span>Время <b>{score(assessment.timingScore)}</b></span>
          {fill ? (
            <>
              <span>Адрес <b>{score(assessment.addressScore)}</b></span>
              <span>Тип происшествия <b>{score(assessment.classificationScore)}</b></span>
              <span>Службы <b>{score(assessment.servicesScore)}</b></span>
            </>
          ) : (
            <>
              <span>Действия <b>{score(assessment.actionsScore)}</b></span>
              <span>Коммуникация <b>{score(assessment.communicationScore)}</b></span>
            </>
          )}
          <span>Грамотность <b>{score(assessment.languageScore)}</b>{assessment.syntaxErrors ? <small> · ошибок: {assessment.syntaxErrors}</small> : null}</span>
          {assessment.source === "TEACHER" && <span>Оценка системы <b>{score(assessment.aiTotalScore)}</b></span>}
        </div>
        {assessment.teacherComment && <p className="teacher-comment"><b>Преподаватель:</b> {assessment.teacherComment}</p>}
        {assessment.teacherCriteria.filter((c) => c.comment || c.score !== null).length > 0 && (
          <ul className="criteria-notes">
            {assessment.teacherCriteria.filter((c) => c.comment || c.score !== null).map((c) => {
              const ai = assessment.aiCriteria.find((a) => a.code === c.code)?.score ?? null;
              return (
                <li key={c.code}>
                  <b>{criterionLabels[c.code] ?? c.code}:</b>{" "}
                  {c.score !== null ? <>{score(c.score)} <small>(система: {score(ai)})</small></> : <small>балл системы {score(ai)}</small>}
                  {c.comment && <> — {c.comment}</>}
                </li>
              );
            })}
          </ul>
        )}
        {!hideIssues && assessment.issues.filter((i) => i.severity !== "INFO").map((issue, index) => {
          const expected = formatIssueValue(issue.expected);
          const actual = formatIssueValue(issue.actual);
          return (
            <p key={index} className={`assessment-issue ${issue.severity === "CRITICAL" ? "critical" : ""}`}>
              {issue.message}
              {(expected || actual) && (
                <small className="issue-compare">
                  {expected && <>эталон: <b>{expected}</b></>}
                  {expected && actual && " · "}
                  {actual && <>факт: <b>{actual}</b></>}
                </small>
              )}
            </p>
          );
        })}
        {!hideIssues && assessment.issues.filter((i) => i.severity === "INFO").length > 0 && (
          <details className="assessment-details">
            <summary>Замечания по тексту ({assessment.issues.filter((i) => i.severity === "INFO").length})</summary>
            {assessment.issues.filter((i) => i.severity === "INFO").map((issue, index) => <p key={index}>{issue.message}</p>)}
          </details>
        )}
        {assessment.recommendations.length > 0 && (
          <>
            <p className="recommendations-title">Рекомендации <small className="muted">по правилам, по каждому виду ошибки</small></p>
            <ul className="recommendations">
              {assessment.recommendations.map((r, index) => <li key={index}>{r}</li>)}
            </ul>
          </>
        )}
        {token && <LlmDebrief token={token} assessmentId={assessment.id} />}
      </div>
    </section>
  );
}
