"use client";

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { API_BASE, WS_BASE, api, type IncidentTypeItem } from "../../lib/api";
import { getMessage } from "../../lib/format";

/** Экран входа — общий для трёх ролей, воспроизводит dds-01.png. */
export function Login({ onLogin, notice }: { onLogin: (token: string) => void; notice?: string }) {
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
        {!error && notice && <p className="login-notice">{notice}</p>}
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
  const body = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    body.current?.scrollTo({ top: 0 });
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === "Escape") onCloseRef.current(); };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("keydown", closeOnEscape);
      document.body.style.overflow = previousOverflow;
      window.scrollTo(scrollX, scrollY);
    };
  }, []);
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) onClose(); }}>
      <section className={`modal ${wide ? "modal-wide" : ""}`} role="dialog" aria-modal="true" aria-label={title}>
        <header><b>{title}</b><button onClick={onClose} aria-label="Закрыть">×</button></header>
        <div className="modal-body" ref={body}>{children}</div>
      </section>
    </div>
  );
}

type CsvValue = string | number | boolean | null | undefined;

/**
 * Выгрузка уже показанной пользователю таблицы. CSV открывается в русском Excel:
 * UTF-8 BOM, разделитель «;», CRLF и защита пользовательских значений от формул.
 */
export function CsvExportButton({ fileName, headers, rows, disabled }: {
  fileName: string;
  headers: string[];
  rows: CsvValue[][];
  disabled?: boolean;
}) {
  const download = () => {
    const escape = (value: CsvValue) => {
      let text = value === null || value === undefined ? "" : String(value);
      if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
      return `"${text.replaceAll('"', '""')}"`;
    };
    const csv = [headers, ...rows].map((row) => row.map(escape).join(";")).join("\r\n");
    const url = URL.createObjectURL(new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    const safeName = fileName.replace(/[\\/:*?"<>|]+/g, "-");
    link.download = safeName.endsWith(".csv") ? safeName : `${safeName}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  };
  return <button type="button" className="csv-button" disabled={disabled || rows.length === 0} onClick={download}>⇩ CSV</button>;
}

/** Верхняя полоса «ГБУ Система 112» + масштаб + имя пользователя; одинаковая на всех экранах. */
export function TopStrip({ label, onLogout, nav }: { label: string; onLogout: () => void; nav?: React.ReactNode }) {
  const [scale, setScale] = useUiScale();
  return (
    <header className="top-strip">
      {nav && <nav className="top-nav">{nav}</nav>}
      <div className="product-name">ГБУ Система 112</div>
      <div className="top-actions">
        <button type="button" className="user-chip" onClick={onLogout} title={`${label} — выйти из системы`}>
          <span className="user-chip-label">{label}</span> <b>×</b>
        </button>
        <div className="scale-control" title="Масштаб интерфейса">
          <button type="button" onClick={() => setScale(scale - 0.1)} disabled={scale <= 0.8} aria-label="Мельче">A−</button>
          <span>{Math.round(scale * 100)}%</span>
          <button type="button" onClick={() => setScale(scale + 0.1)} disabled={scale >= 1.6} aria-label="Крупнее">A+</button>
        </div>
      </div>
    </header>
  );
}

const SCALE_KEY = "arm112-ui-scale";

/** Масштаб по умолчанию: на узких экранах интерфейс АРМ с 10–12px шрифтами слишком мелкий. */
function defaultScale() {
  if (typeof window === "undefined") return 1;
  const width = window.screen?.width || window.innerWidth;
  if (width <= 1366) return 1.2;
  if (width <= 1600) return 1.1;
  return 1;
}

/**
 * Масштаб интерфейса: CSS zoom на корне через переменную --ui-scale, значение запоминается в localStorage.
 * Авто-значение зависит от ширины экрана, пользователь меняет кнопками A− / A+ в шапке.
 */
export function useUiScale() {
  const [scale, setScaleState] = useState(1);
  useEffect(() => {
    let saved: number | null = null;
    try {
      const raw = localStorage.getItem(SCALE_KEY);
      saved = raw ? Number(raw) : null;
    } catch { saved = null; }
    const initial = saved && saved >= 0.8 && saved <= 1.6 ? saved : defaultScale();
    queueMicrotask(() => setScaleState(initial));
  }, []);
  useEffect(() => {
    document.documentElement.style.setProperty("--ui-scale", String(scale));
  }, [scale]);
  const setScale = useCallback((next: number) => {
    const clamped = Math.round(Math.max(0.8, Math.min(1.6, next)) * 10) / 10;
    setScaleState(clamped);
    try { localStorage.setItem(SCALE_KEY, String(clamped)); } catch { /* приватное окно */ }
  }, []);
  return [scale, setScale] as const;
}

let incidentTypesCache: Map<string, string> | null = null;

/** Подписи типов происшествий по id — чтобы в интерфейсе не было fire.apartment. */
export function useIncidentTypeLabels(token: string) {
  const [labels, setLabels] = useState<Map<string, string>>(incidentTypesCache ?? new Map());
  useEffect(() => {
    if (incidentTypesCache) return;
    api.incidentTypes(token).then((items: IncidentTypeItem[]) => {
      incidentTypesCache = new Map(items.map((t) => [t.id, t.label]));
      setLabels(incidentTypesCache);
    }).catch(() => undefined);
  }, [token]);
  return useCallback((id: string) => labels.get(id) ?? id, [labels]);
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

/**
 * Звонок входящего вызова: тон 425 Гц (как в телефонной сети РФ) 1 с / пауза 4 с через WebAudio.
 * Браузер разрешает звук после жеста пользователя — вход уже был. Выключатель хранится в localStorage.
 */
export function useRingTone(active: boolean) {
  const [enabled, setEnabled] = useState(true);
  useEffect(() => {
    try {
      queueMicrotask(() => setEnabled(window.localStorage.getItem("arm112-ring") !== "off"));
    } catch { /* приватный режим — звук включён */ }
  }, []);
  const toggle = useCallback(() => {
    setEnabled((value) => {
      try { window.localStorage.setItem("arm112-ring", value ? "off" : "on"); } catch { /* ignore */ }
      return !value;
    });
  }, []);
  useEffect(() => {
    if (!active || !enabled) return;
    let context: AudioContext | null = null;
    let timer: number | undefined;
    try {
      context = new AudioContext();
      const ring = () => {
        if (!context) return;
        void context.resume();
        const osc = context.createOscillator();
        const gain = context.createGain();
        osc.frequency.value = 425;
        gain.gain.value = 0.08;
        osc.connect(gain).connect(context.destination);
        osc.start();
        osc.stop(context.currentTime + 1);
      };
      ring();
      timer = window.setInterval(ring, 5000);
    } catch { /* нет WebAudio — тихо */ }
    return () => {
      if (timer) window.clearInterval(timer);
      void context?.close().catch(() => undefined);
    };
  }, [active, enabled]);
  return { enabled, toggle };
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
