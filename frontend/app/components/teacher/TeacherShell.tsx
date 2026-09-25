"use client";

import { useState } from "react";
import type { User } from "../../../lib/api";
import { TopStrip } from "../common";
import { TeacherAnalytics } from "./Analytics";
import { Lessons } from "./Lessons";
import { Scenarios } from "./Scenarios";
import { Materials } from "./Materials";

type View = "lessons" | "analytics" | "scenarios" | "materials";

/** Рабочее место преподавателя: занятия, аналитика, сценарии, материалы. */
export function TeacherShell({ token, user, onLogout }: { token: string; user: User; onLogout: () => void }) {
  const [view, setView] = useState<View>("lessons");
  const nav = (
    <>
      <button className={view === "lessons" ? "active" : ""} onClick={() => setView("lessons")}>Занятия</button>
      <button className={view === "analytics" ? "active" : ""} onClick={() => setView("analytics")}>Аналитика</button>
      <button className={view === "scenarios" ? "active" : ""} onClick={() => setView("scenarios")}>Сценарии</button>
      <button className={view === "materials" ? "active" : ""} onClick={() => setView("materials")}>Материалы</button>
    </>
  );
  return (
    <main className="arm-shell">
      <TopStrip label={`${user.displayName} · преподаватель`} onLogout={onLogout} nav={nav} />
      {view === "lessons" && <Lessons token={token} />}
      {view === "analytics" && <TeacherAnalytics token={token} />}
      {view === "scenarios" && <Scenarios token={token} />}
      {view === "materials" && <Materials token={token} />}
    </main>
  );
}
