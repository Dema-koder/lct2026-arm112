"use client";

import { useCallback, useEffect, useState } from "react";
import { api, onUnauthorized, type User } from "../lib/api";
import { Login } from "./components/common";
import { TraineeShell } from "./components/trainee/TraineeShell";
import { TeacherShell } from "./components/teacher/TeacherShell";
import { AdminShell } from "./components/admin/AdminShell";

/**
 * Точка входа: восстанавливает токен, определяет роль и открывает нужное рабочее место.
 * Обучающийся — симулятор АРМ-112, преподаватель и администратор — свои экраны в том же стиле.
 */
export default function Home() {
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [loginNotice, setLoginNotice] = useState("");

  const loadUser = useCallback(async (value: string) => {
    const me = await api.me(value);
    setUser(me);
    setToken(value);
  }, []);

  useEffect(() => {
    const saved = sessionStorage.getItem("arm112-token");
    if (!saved) {
      queueMicrotask(() => setRestoring(false));
      return;
    }
    queueMicrotask(() => {
      loadUser(saved)
        .catch(() => sessionStorage.removeItem("arm112-token"))
        .finally(() => setRestoring(false));
    });
  }, [loadUser]);

  const onLogin = async (newToken: string) => {
    sessionStorage.setItem("arm112-token", newToken);
    await loadUser(newToken);
  };

  const logout = useCallback((reason?: string) => {
    sessionStorage.removeItem("arm112-token");
    setToken(null);
    setUser(null);
    setLoginNotice(typeof reason === "string" ? reason : "");
  }, []);

  // 401 на любом запросе (отозванный токен, восстановление копии) — сразу экран входа
  useEffect(() => {
    onUnauthorized((reason) => logout(reason));
    return () => onUnauthorized(null);
  }, [logout]);

  if (restoring) {
    return <div className="boot-screen">Подключение к учебному серверу…</div>;
  }
  if (!token || !user) {
    return <Login onLogin={onLogin} notice={loginNotice} />;
  }
  switch (user.role) {
    case "TEACHER":
      return <TeacherShell token={token} user={user} onLogout={() => logout()} />;
    case "ADMIN":
      return <AdminShell token={token} user={user} onLogout={() => logout()} />;
    default:
      return <TraineeShell token={token} user={user} onLogout={() => logout()} />;
  }
}
