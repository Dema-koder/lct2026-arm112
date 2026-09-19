"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { API_BASE, WS_BASE, api } from "../../lib/api";
import { getMessage } from "../../lib/format";

/** Экран входа — общий для трёх ролей, воспроизводит dds-01.png. */
export function Login({ onLogin }: { onLogin: (token: string) => void }) {
  const [username, setUsername] = useState("trainee");
  const [password, setPassword] = useState("trainee");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const response = await api.login(username, password);
      onLogin(response.accessToken);
    } catch (err) {
      setError(getMessage(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-screen">
      <div className="skyline skyline-back">
        {[14, 24, 13, 31, 19, 25, 14, 34, 20, 29, 12].map((height, index) => (
          <i key={index} style={{ height: `${height}vh` }} />
        ))}
      </div>
      <div className="skyline skyline-front">
        {[18, 12, 23, 15, 31, 19, 11, 21].map((height, index) => (
          <i key={index} style={{ height: `${height}vh` }} />
        ))}
      </div>
      <div className="helicopter" aria-hidden="true">🚁</div>
      <form className="login-panel" onSubmit={submit}>
        <div className="login-title"><b>112</b><span>ВХОД В СИСТЕМУ</span></div>
        <label>
          <span>логин:</span>
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
        </label>
        <label>
          <span>пароль:</span>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
        </label>
        <button type="submit" disabled={loading}>{loading ? "ПОДКЛЮЧЕНИЕ…" : "ВОЙТИ"}</button>
        {error && <p className="login-error">{error}</p>}
        <div className="support-copy">
          Техподдержка<br />
          +7 (495) 197-89-81<br />
          (многоканальный)<br />
          <a href="mailto:hd-112@mos.ru">hd-112@mos.ru</a>
        </div>
      </form>
      <div className="login-version">
        ЛЦТ 2026 · учебный контур ·{" "}
        <a href={`${API_BASE.replace("/api/v1", "")}/swagger-ui.html`} target="_blank" rel="noreferrer">Swagger</a>
      </div>
    </main>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}>
      <section className={`modal ${wide ? "modal-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header><b>{title}</b><button onClick={onClose} aria-label="Закрыть">×</button></header>
        <div className="modal-body">{children}</div>
      </section>
    </div>
  );
}

/** Верхняя полоса «ГБУ Система 112» + имя пользователя; одинаковая на всех экранах. */
export function TopStrip({ label, onLogout, nav }: { label: string; onLogout: () => void; nav?: React.ReactNode }) {
  return (
    <header className="top-strip">
      <div className="product-name">ГБУ Система 112</div>
      {nav && <nav className="top-nav">{nav}</nav>}
      <button className="user-chip" onClick={onLogout} title="Выйти из системы">
        {label} <b>×</b>
      </button>
    </header>
  );
}

export function ErrorBanner({ error, onClose }: { error: string; onClose: () => void }) {
  if (!error) return null;
  return (
    <div className="error-banner" role="alert">
      <b>Ошибка:</b> {error}
      <button onClick={onClose} aria-label="Закрыть">×</button>
    </div>
  );
}

export function Notice({ text }: { text: string }) {
  return text ? <div className="notice-toast">✓ {text}</div> : null;
}

/** Секундный тик для таймеров. */
export function useClock() {
  const [now, setNow] = useState(0);
  useEffect(() => {
    queueMicrotask(() => setNow(Date.now()));
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

export function useNotice() {
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);
  return [notice, setNotice] as const;
}

/**
 * WebSocket с одноразовым билетом и переподключением. Сервер доставляет события адресно:
 * обучающемуся — его сессии, преподавателю — его занятий.
 */
export function useSocket(token: string | null, onEvent: (event: { type: string; payload?: Record<string, unknown>; sessionId?: string | null }) => void) {
  const handler = useRef(onEvent);
  useEffect(() => {
    handler.current = onEvent;
  }, [onEvent]);
  useEffect(() => {
    if (!token) return;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | undefined;
    let closed = false;
    const connect = async () => {
      try {
        const { ticket } = await api.wsTicket(token);
        if (closed) return;
        socket = new WebSocket(`${WS_BASE}?ticket=${encodeURIComponent(ticket)}`);
        socket.onmessage = (event) => {
          const message = JSON.parse(event.data);
          if (message.type === "system.connected") return;
          handler.current(message);
        };
        socket.onclose = () => {
          if (!closed) reconnectTimer = window.setTimeout(connect, 1800);
        };
      } catch {
        if (!closed) reconnectTimer = window.setTimeout(connect, 3000);
      }
    };
    connect();
    return () => {
      closed = true;
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [token]);
}

/** Обёртка над async-действием: флаг занятости и ошибка в одном месте. */
export function useAction(setError: (value: string) => void) {
  const [working, setWorking] = useState(false);
  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | undefined> => {
    setWorking(true);
    try {
      const result = await action();
      setError("");
      return result;
    } catch (err) {
      setError(getMessage(err));
      return undefined;
    } finally {
      setWorking(false);
    }
  }, [setError]);
  return [working, run] as const;
}
