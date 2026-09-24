"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type AuditEntry, type BackupInfo, type Group, type Role, type SystemHealth, type User, type UserAdminView } from "../../../lib/api";
import { bytes, dateTime, roleLabels } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, TopStrip, useAction, useNotice } from "../common";

type View = "users" | "groups" | "settings" | "audit" | "system";

/** Рабочее место администратора — всё через UI (решение №9), в стиле остальных экранов. */
export function AdminShell({ token, user, onLogout }: { token: string; user: User; onLogout: () => void }) {
  const [view, setView] = useState<View>("users");
  const nav = (
    <>
      {(["users", "groups", "settings", "audit", "system"] as View[]).map((v) => (
        <button key={v} className={view === v ? "active" : ""} onClick={() => setView(v)}>
          {{ users: "Пользователи", groups: "Группы", settings: "Настройки", audit: "Журнал", system: "Система" }[v]}
        </button>
      ))}
    </>
  );
  return (
    <main className="arm-shell">
      <TopStrip label={`${user.displayName} · администратор`} onLogout={onLogout} nav={nav} />
      {view === "users" && <Users token={token} self={user} />}
      {view === "groups" && <Groups token={token} />}
      {view === "settings" && <Settings token={token} />}
      {view === "audit" && <Audit token={token} />}
      {view === "system" && <System token={token} />}
    </main>
  );
}

function Users({ token, self }: { token: string; self: User }) {
  const [items, setItems] = useState<UserAdminView[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [form, setForm] = useState({ login: "", password: "", displayName: "", role: "TRAINEE" as Role, workstationNumber: "", groupId: "" });
  const [editing, setEditing] = useState<UserAdminView | null>(null);
  const [resetFor, setResetFor] = useState<UserAdminView | null>(null);
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const [u, g] = await Promise.all([api.admin.users(token), api.admin.groups(token)]);
    setItems(u);
    setGroups(g);
  }, [token]);
  useEffect(() => { void run(load); }, [load, run]);

  const create = () => run(async () => {
    await api.admin.createUser(token, {
      login: form.login.trim(), password: form.password, displayName: form.displayName.trim(), role: form.role,
      workstationNumber: form.role === "TRAINEE" && form.workstationNumber ? form.workstationNumber : null,
      groupId: form.role === "TRAINEE" && form.groupId ? form.groupId : null,
    });
    setForm({ login: "", password: "", displayName: "", role: "TRAINEE", workstationNumber: "", groupId: "" });
    setNotice("Учётная запись создана");
    await load();
  });
  const toggle = (u: UserAdminView) => run(async () => {
    await (u.active ? api.admin.block(token, u.id) : api.admin.unblock(token, u.id));
    await load();
  });
  const saveEdit = () => editing && run(async () => {
    await api.admin.updateUser(token, editing.id, {
      displayName: editing.displayName, role: editing.role,
      workstationNumber: editing.workstationNumber || null, groupId: editing.groupId || null,
    });
    setEditing(null);
    setNotice("Сохранено");
    await load();
  });
  const reset = () => resetFor && run(async () => {
    await api.admin.resetPassword(token, resetFor.id, newPassword);
    setResetFor(null);
    setNewPassword("");
    setNotice("Пароль сброшен");
  });

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Пользователи</b></div>
      <div className="form-grid five">
        <label><span>Логин</span><input value={form.login} onChange={(e) => setForm({ ...form, login: e.target.value })} /></label>
        <label><span>Пароль</span><input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></label>
        <label><span>ФИО</span><input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></label>
        <label><span>Роль</span>
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
            {(["TRAINEE", "TEACHER", "ADMIN"] as Role[]).map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
          </select></label>
        {form.role === "TRAINEE" && (
          <>
            <label><span>Номер АРМ</span><input value={form.workstationNumber} onChange={(e) => setForm({ ...form, workstationNumber: e.target.value })} placeholder="за каким сидит" /></label>
            <label><span>Группа</span>
              <select value={form.groupId} onChange={(e) => setForm({ ...form, groupId: e.target.value })}>
                <option value="">без группы</option>
                {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select></label>
          </>
        )}
        <div className="dialog-actions"><button className="primary" disabled={working || !form.login || form.password.length < 4 || !form.displayName} onClick={create}>Создать</button></div>
      </div>
      <table className="data-table">
        <thead><tr><th>Логин</th><th>ФИО</th><th>Роль</th><th>АРМ</th><th>Группа</th><th>Создан</th><th>Активен</th><th /></tr></thead>
        <tbody>
          {items.map((u) => (
            <tr key={u.id} className={u.active ? "" : "inactive"}>
              <td><b>{u.login}</b></td><td>{u.displayName}</td><td>{roleLabels[u.role]}</td>
              <td>{u.workstationNumber ?? "—"}</td>
              <td>{groups.find((g) => g.id === u.groupId)?.name ?? "—"}</td>
              <td>{dateTime(u.createdAt)}</td>
              <td><label className="switch"><input type="checkbox" checked={u.active} disabled={u.id === self.id || working} onChange={() => toggle(u)} /><span /></label></td>
              <td className="row-actions">
                <button className="ghost" onClick={() => setEditing(u)}>изменить</button>
                <button className="ghost" onClick={() => setResetFor(u)}>пароль</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editing && (
        <Modal title={`Учётная запись ${editing.login}`} onClose={() => setEditing(null)}>
          <div className="form-grid">
            <label className="wide"><span>ФИО</span><input value={editing.displayName} onChange={(e) => setEditing({ ...editing, displayName: e.target.value })} /></label>
            <label><span>Роль</span>
              <select value={editing.role} onChange={(e) => setEditing({ ...editing, role: e.target.value as Role })}>
                {(["TRAINEE", "TEACHER", "ADMIN"] as Role[]).map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
              </select></label>
            <label><span>Номер АРМ</span><input value={editing.workstationNumber ?? ""} onChange={(e) => setEditing({ ...editing, workstationNumber: e.target.value })} /></label>
            <label className="wide"><span>Группа</span>
              <select value={editing.groupId ?? ""} onChange={(e) => setEditing({ ...editing, groupId: e.target.value || null })}>
                <option value="">без группы</option>
                {groups.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select></label>
          </div>
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setEditing(null)}>Отмена</button>
            <button className="primary" disabled={working} onClick={saveEdit}>Сохранить</button>
          </div>
        </Modal>
      )}
      {resetFor && (
        <Modal title={`Новый пароль для ${resetFor.login}`} onClose={() => setResetFor(null)}>
          <input className="status-form-comment w-full" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} placeholder="не короче 4 символов" />
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setResetFor(null)}>Отмена</button>
            <button className="primary" disabled={working || newPassword.length < 4} onClick={reset}>Сбросить</button>
          </div>
        </Modal>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function Groups({ token }: { token: string }) {
  const [groups, setGroups] = useState<Group[]>([]);
  const [teachers, setTeachers] = useState<UserAdminView[]>([]);
  const [name, setName] = useState("");
  const [teacherId, setTeacherId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const [g, t] = await Promise.all([api.admin.groups(token), api.admin.users(token, "TEACHER")]);
    setGroups(g);
    setTeachers(t);
    if (!teacherId && t[0]) setTeacherId(t[0].id);
  }, [token, teacherId]);
  useEffect(() => { void run(load); }, [load, run]);

  const create = () => run(async () => {
    await api.admin.createGroup(token, name.trim(), teacherId);
    setName("");
    setNotice("Группа создана");
    await load();
  });
  const reassign = (g: Group, next: string) => run(async () => { await api.admin.updateGroup(token, g.id, g.name, next); await load(); });

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Группы</b></div>
      <div className="form-grid">
        <label><span>Название</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label><span>Преподаватель</span>
          <select value={teacherId} onChange={(e) => setTeacherId(e.target.value)}>
            {teachers.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}
          </select></label>
        <div className="dialog-actions"><button className="primary" disabled={working || !name.trim() || !teacherId} onClick={create}>Создать</button></div>
      </div>
      <table className="data-table">
        <thead><tr><th>Группа</th><th>Преподаватель</th><th>Обучающиеся</th></tr></thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g.id}>
              <td><b>{g.name}</b></td>
              <td>
                <select value={g.teacherId} onChange={(e) => reassign(g, e.target.value)}>
                  {teachers.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}
                </select>
              </td>
              <td>{g.members.map((m) => `${m.displayName} (АРМ ${m.workstationNumber ?? "—"})`).join(", ") || <span className="muted">пусто — назначьте группу в карточке пользователя</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

const SETTING_LABELS: Record<string, string> = {
  "audit.retention_days": "Хранение журнала аудита, дней (не меньше 180)",
  "telephony.ringing_ms": "Телефония: гудки, мс",
  "simulation.card_open_ms": "Симуляция: открытие карточки, мс",
  "simulation.card_arrival_ms": "Симуляция: базовый интервал карточек, мс",
  "simulation.service_time_scale_percent": "Симуляция: масштаб времени служб, %",
  "telephony.connect_ms": "Телефония: соединение, мс",
  "telephony.acknowledge_ms": "Телефония: «информация принята», мс",
  "sla.acceptance_seconds": "Норматив принятия карточки, с",
  "sla.processing_seconds": "Норматив отработки карточки, с",
  "logging.level": "Уровень логирования (INFO / DEBUG / WARN)",
};

/** Настройки сохраняются по потере фокуса — без кнопки «Сохранить» (решение №11). */
function Settings({ token }: { token: string }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  useEffect(() => { api.admin.settings(token).then(setValues).catch((e) => setError(e.message)); }, [token]);

  const commit = async (key: string, value: string) => {
    try {
      setValues(await api.admin.updateSettings(token, { [key]: value }));
      setNotice("Сохранено");
      setError("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Не удалось сохранить");
      setValues(await api.admin.settings(token));
    }
  };

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Настройки</b><small className="muted">применяются сразу, сохраняются при уходе из поля</small></div>
      <div className="form-grid two">
        {Object.entries(values).map(([key, value]) => (
          <label key={key}><span>{SETTING_LABELS[key] ?? key}</span>
            <input defaultValue={value} key={key + value} onBlur={(e) => { if (e.target.value !== value) void commit(key, e.target.value); }} />
          </label>
        ))}
      </div>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function Audit({ token }: { token: string }) {
  const [items, setItems] = useState<AuditEntry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [role, setRole] = useState("");
  const [action, setAction] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async (append: boolean, from?: string | null) => {
    try {
      const page = await api.admin.audit(token, { role: role || undefined, action: action || undefined, cursor: from ?? undefined, limit: 50 });
      setItems(append ? (prev) => [...prev, ...page.items] : page.items);
      setCursor(page.nextCursor);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Ошибка");
    }
  }, [token, role, action]);
  useEffect(() => { void Promise.resolve().then(() => load(false)); }, [load]);

  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Журнал действий</b>
        <select value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="">все роли</option>
          {(["ADMIN", "TEACHER", "TRAINEE"] as Role[]).map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
        </select>
        <input value={action} onChange={(e) => setAction(e.target.value)} placeholder="фильтр по действию, например cards" />
      </div>
      <table className="data-table audit">
        <thead><tr><th>Когда</th><th>Кто</th><th>Роль</th><th>Действие</th><th>Статус</th><th>IP</th><th>Данные</th></tr></thead>
        <tbody>
          {items.map((e) => (
            <tr key={e.id}>
              <td>{dateTime(e.occurredAt)}</td><td>{e.actorLogin ?? "—"}</td><td>{e.actorRole ? roleLabels[e.actorRole] ?? e.actorRole : "—"}</td>
              <td><code>{e.action}</code></td><td>{e.httpStatus ?? "—"}</td><td>{e.clientIp ?? "—"}</td>
              <td className="ellipsis" title={e.payload ?? ""}>{e.payload ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {cursor && <div className="dialog-actions"><button className="secondary" onClick={() => load(true, cursor)}>Показать ещё</button></div>}
      <ErrorBanner error={error} onClose={() => setError("")} />
    </section>
  );
}

function System({ token }: { token: string }) {
  const [health, setHealth] = useState<SystemHealth | null>(null);
  const [backups, setBackups] = useState<BackupInfo[]>([]);
  const [log, setLog] = useState<string[]>([]);
  const [restoreFile, setRestoreFile] = useState<string | null>(null);
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const [h, b, l] = await Promise.all([api.admin.health(token), api.admin.backups(token), api.admin.log(token, 100)]);
    setHealth(h);
    setBackups(b);
    setLog(l.lines);
  }, [token]);
  useEffect(() => { void run(load); }, [load, run]);

  const backup = () => run(async () => { const b = await api.admin.createBackup(token); setNotice(`Копия ${b.fileName} создана`); await load(); });
  const restore = () => restoreFile && run(async () => {
    await api.admin.restore(token, restoreFile);
    setRestoreFile(null);
    setConfirm("");
    setNotice("База восстановлена");
    await load();
  });

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Состояние системы</b><button className="ghost" onClick={() => run(load)}>обновить</button></div>
      {health && (
        <div className="health-grid">
          <span>Сервис <b className={health.status === "UP" ? "ok" : "bad"}>{health.status}</b></span>
          <span>База данных <b className={health.database === "UP" ? "ok" : "bad"}>{health.database}</b></span>
          <span>WebSocket-подключений <b>{health.openSockets}</b></span>
          <span>Активных сессий <b>{health.activeSessions}</b></span>
          <span>Версия <b>{health.version}</b></span>
          <span>Время сервера <b>{dateTime(health.serverTime)}</b></span>
        </div>
      )}
      <div className="panel-head"><b>Резервные копии</b><small className="muted">ежедневно в 02:00 автоматически</small><span className="spacer" /><button className="primary-button" disabled={working} onClick={backup}>Сделать копию сейчас</button></div>
      <table className="data-table">
        <thead><tr><th>Файл</th><th>Размер</th><th>Создана</th><th /></tr></thead>
        <tbody>
          {backups.length === 0 && <tr><td colSpan={4} className="muted">Копий ещё нет</td></tr>}
          {backups.map((b) => (
            <tr key={b.fileName}><td><b>{b.fileName}</b></td><td>{bytes(b.sizeBytes)}</td><td>{dateTime(b.createdAt)}</td>
              <td><button className="ghost danger" onClick={() => setRestoreFile(b.fileName)}>восстановить</button></td></tr>
          ))}
        </tbody>
      </table>
      <div className="panel-head"><b>Лог сервера</b></div>
      <pre className="log-tail">{log.join("\n") || "лог пуст"}</pre>

      {restoreFile && (
        <Modal title="Восстановление из копии" onClose={() => setRestoreFile(null)}>
          <p className="dialog-copy">Текущие данные будут <b>безвозвратно заменены</b> содержимым копии {restoreFile}. Для подтверждения введите RESTORE.</p>
          <input className="status-form-comment w-full" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setRestoreFile(null)}>Отмена</button>
            <button className="call-button danger" disabled={working || confirm !== "RESTORE"} onClick={restore}>Восстановить</button>
          </div>
        </Modal>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}
