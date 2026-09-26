"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, api, type TraineeContext, type User } from "../../../lib/api";
import { getMessage } from "../../../lib/format";
import { ErrorBanner, TopStrip, useSocket } from "../common";
import { DdsWorkspace } from "../dds/DdsWorkspace";
import { FillWorkspace } from "../fill/FillWorkspace";
import { TraineeAnalytics } from "./Analytics";
import { Results } from "./Results";

/**
 * Рабочее место обучающегося: активное занятие открывает нужный симулятор
 * (оператор 112 или ДДС), без занятия — ожидание, результаты и аналитика.
 */
export function TraineeShell({ token, user, onLogout }: { token: string; user: User; onLogout: () => void }) {
  const [context, setContext] = useState<TraineeContext | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<"work" | "results" | "analytics">("work");
  const [version, setVersion] = useState(0);

  const loadContext = useCallback(async () => {
    try {
      const next = await api.context(token);
      setContext(next);
      setError("");
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onLogout();
      else setError(getMessage(err));
    } finally {
      setLoading(false);
    }
  }, [token, onLogout]);

  useEffect(() => {
    void Promise.resolve().then(loadContext);
  }, [loadContext]);

  useSocket(token, (event) => {
    if (["training.session_started", "training.session_state_changed", "results.published", "assessment.updated"].includes(event.type)) {
      void loadContext();
      setVersion((v) => v + 1);
    }
  });

  if (loading) return <div className="boot-screen">Загрузка рабочего места…</div>;

  const session = context?.activeSession ?? null;
  const label = `${user.displayName} · АРМ ${context?.workstation.number ?? user.workstationNumber ?? "—"}`;
  const nav = (
    <>
      <button className={view === "work" ? "active" : ""} onClick={() => setView("work")}>Рабочее место</button>
      <button className={view === "results" ? "active" : ""} onClick={() => setView("results")}>Мои результаты</button>
      <button className={view === "analytics" ? "active" : ""} onClick={() => setView("analytics")}>Аналитика</button>
    </>
  );

  if (view === "results") {
    return (
      <main className="arm-shell">
        <TopStrip label={label} onLogout={onLogout} nav={nav} />
        <Results token={token} refreshKey={version} />
        <ErrorBanner error={error} onClose={() => setError("")} />
      </main>
    );
  }

  if (view === "analytics") {
    return (
      <main className="arm-shell">
        <TopStrip label={label} onLogout={onLogout} nav={nav} />
        <TraineeAnalytics token={token} refreshKey={version} />
        <ErrorBanner error={error} onClose={() => setError("")} />
      </main>
    );
  }

  if (!session) {
    return (
      <main className="arm-shell">
        <TopStrip label={label} onLogout={onLogout} nav={nav} />
        <section className="waiting-screen">
          <b>Занятие ещё не назначено</b>
          <span>Как только преподаватель начнёт занятие, карточка появится здесь автоматически.</span>
        </section>
        <ErrorBanner error={error} onClose={() => setError("")} />
      </main>
    );
  }

  if (session.mode === "CARD_FILL") {
    return (
      <FillWorkspace
        key={session.id}
        token={token}
        context={context!}
        onLogout={onLogout}
        onReload={loadContext}
        nav={nav}
        label={label}
      />
    );
  }
  return (
    <DdsWorkspace
      key={session.id}
      token={token}
      context={context!}
      onLogout={onLogout}
      onReload={loadContext}
      nav={nav}
      label={label}
    />
  );
}
