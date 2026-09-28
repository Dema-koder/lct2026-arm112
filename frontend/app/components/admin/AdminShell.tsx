"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type AdminCalibrationState, type AlertCallAttempt, type AlertConfiguration, type AuditEntry, type BackupInfo, type Group, type LessonMode, type ManagedService, type MockPhoneGatewaySettings, type Role, type ServiceEvent, type SystemHealth, type User, type UserAdminView } from "../../../lib/api";
import { bytes, dateTime, roleLabels } from "../../../lib/format";
import { ErrorBanner, Modal, Notice, TopStrip, useAction, useNotice } from "../common";

type View = "users" | "groups" | "services" | "calibration" | "settings" | "audit" | "system";

/** Рабочее место администратора — всё через UI (решение №9), в стиле остальных экранов. */
export function AdminShell({ token, user, onLogout }: { token: string; user: User; onLogout: () => void }) {
  const [view, setView] = useState<View>("users");
  const nav = (
    <>
      {(["users", "groups", "services", "calibration", "settings", "audit", "system"] as View[]).map((v) => (
        <button key={v} className={view === v ? "active" : ""} onClick={() => setView(v)}>
          {{ users: "Пользователи", groups: "Группы", services: "Сервисы", calibration: "Коррекция ИИ", settings: "Настройки", audit: "Журнал", system: "Система" }[v]}
        </button>
      ))}
    </>
  );
  return (
    <main className="arm-shell">
      <TopStrip label={`${user.displayName} · администратор`} onLogout={onLogout} nav={nav} />
      {view === "users" && <Users token={token} self={user} />}
      {view === "groups" && <Groups token={token} />}
      {view === "services" && <Services token={token} />}
      {view === "calibration" && <AssessmentCalibration token={token} />}
      {view === "settings" && <Settings token={token} />}
      {view === "audit" && <Audit token={token} />}
      {view === "system" && <System token={token} />}
    </main>
  );
}

const MODE_LABELS: Record<LessonMode, string> = {
  CARD_FILL: "Оператор 112 · заполнение карточки",
  CARD_ACTIONS: "ДДС · действия с карточкой",
};

function AssessmentCalibration({ token }: { token: string }) {
  const [mode, setMode] = useState<LessonMode>("CARD_FILL");
  const [state, setState] = useState<AdminCalibrationState | null>(null);
  const [confirmation, setConfirmation] = useState<"activate" | "deactivate" | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const load = useCallback(() => run(async () => setState(await api.admin.calibration(token, mode))), [mode, run, token]);
  useEffect(() => { void load(); }, [load]);

  const execute = () => confirmation && run(async () => {
    const updated = confirmation === "activate"
      ? await api.admin.activateCalibration(token, mode)
      : await api.admin.deactivateCalibration(token, mode);
    setState(updated);
    setNotice(confirmation === "activate"
      ? `Версия ${updated.active?.version ?? ""} включена для будущих оценок`
      : "Коррекция отключена; новые оценки снова выставляются без поправок");
    setConfirmation(null);
  });

  const candidate = state?.candidate;
  return (
    <section className="panel-page calibration-page">
      <div className="panel-head">
        <b>Коррекция оценки ИИ</b>
        <small className="muted">обучение на подтверждённых преподавателем баллах</small>
        <span className="spacer" />
        <select aria-label="Режим занятия" value={mode} onChange={(event) => setMode(event.target.value as LessonMode)}>
          {Object.entries(MODE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <button className="secondary" disabled={working} onClick={load}>Обновить расчёт</button>
      </div>
      {error && <ErrorBanner message={error} />}
      {notice && <Notice message={notice} />}
      <div className="calibration-explainer">
        <b>Как это работает</b>
        <p>Правильной считается оценка преподавателя. Система сравнивает её с исходным баллом ИИ и предлагает поправки отдельно по каждому критерию.</p>
        <p>Новая версия применяется только к будущим оценкам. Уже завершённые занятия и оценки преподавателя не изменяются.</p>
      </div>
      <div className={`calibration-status ${state?.active ? "active" : "inactive"}`}>
        <div><span>Текущая версия</span><b>{state?.active ? `Версия ${state.active.version}` : "Коррекция выключена"}</b></div>
        <div><span>Обучающих оценок</span><b>{state?.active?.assessments ?? "—"}</b></div>
        <div><span>Средняя ошибка</span><b>{state?.active?.maeBefore == null ? "—" : `${state.active.maeBefore.toFixed(1)} → ${state.active.maeAfter?.toFixed(1)}`}</b></div>
        <div><span>Включена</span><b>{state?.active?.activatedAt ? dateTime(state.active.activatedAt) : "—"}</b></div>
      </div>
      <div className="panel-head"><b>Новый расчёт</b><small className="muted">учтено оценок: {candidate?.assessments ?? 0}</small></div>
      {candidate?.criteria.length ? (
        <table className="data-table calibration-table">
          <thead><tr><th>Критерий</th><th>Сравнений</th><th>Ошибка до</th><th>Ошибка после</th><th>Улучшение</th><th>Поправка</th></tr></thead>
          <tbody>{candidate.criteria.map((criterion) => (
            <tr key={criterion.code}>
              <td><b>{criterion.label}</b></td><td>{criterion.pairs}</td>
              <td>{criterion.maeBefore.toFixed(1)}</td><td>{criterion.maeAfter.toFixed(1)}</td>
              <td className="positive">−{criterion.improvementPercent.toFixed(1)}%</td>
              <td><code>{criterion.slope.toFixed(3)} × балл {criterion.intercept < 0 ? "−" : "+"} {Math.abs(criterion.intercept).toFixed(2)}</code></td>
            </tr>
          ))}</tbody>
        </table>
      ) : <p className="calibration-empty">Безопасную поправку пока нельзя рассчитать. Нужны оценки преподавателей по критериям.</p>}
      {!!candidate?.skipped.length && (
        <details className="calibration-skipped" open={!candidate.criteria.length}>
          <summary>Почему отдельные критерии не готовы ({candidate.skipped.length})</summary>
          <ul>{candidate.skipped.map((reason) => <li key={reason}>{reason}</li>)}</ul>
        </details>
      )}
      <div className="dialog-actions calibration-actions">
        {state?.active && <button className="danger" disabled={working} onClick={() => setConfirmation("deactivate")}>Отключить коррекцию</button>}
        <button className="primary" disabled={working || !candidate?.criteria.length} onClick={() => setConfirmation("activate")}>Обучить и включить новую версию</button>
      </div>
      {!!state?.history.length && <details className="calibration-history"><summary>История версий ({state.history.length})</summary>
        <table className="data-table"><thead><tr><th>Версия</th><th>Состояние</th><th>Оценок</th><th>Ошибка</th><th>Создана</th></tr></thead>
          <tbody>{state.history.map((item) => <tr key={item.id}><td>v{item.version}</td><td>{item.active ? "активна" : "выключена"}</td><td>{item.assessments}</td><td>{item.maeBefore?.toFixed(1)} → {item.maeAfter?.toFixed(1)}</td><td>{dateTime(item.createdAt)}</td></tr>)}</tbody>
        </table></details>}
      {confirmation && <Modal title={confirmation === "activate" ? "Включить новую коррекцию?" : "Отключить коррекцию?"} onClose={() => setConfirmation(null)}>
        <p>{confirmation === "activate"
          ? "Новая версия будет применяться только к оценкам ИИ, созданным после включения. Старые результаты останутся без изменений."
          : "Будущие оценки будут выставляться без поправок. История версий сохранится."}</p>
        <div className="dialog-actions"><button className="secondary" onClick={() => setConfirmation(null)}>Отмена</button><button className={confirmation === "activate" ? "primary" : "danger"} disabled={working} onClick={execute}>Подтвердить</button></div>
      </Modal>}
    </section>
  );
}

const SERVICE_ACTION_LABELS = { START: "Запустить", STOP: "Остановить", RESTART: "Перезапустить" } as const;
const CALL_STATUS_LABELS = { ACCEPTED: "Шлюз принял", ANSWERED: "Ответили", NOT_ANSWERED: "Не ответили", FAILED: "Ошибка" } as const;
const CALL_TRIGGER_LABELS: Record<AlertCallAttempt["triggerType"], string> = {
  TEST: "Тест", SERVICE_PROBLEM: "Сбой сервиса", SERVICE_RECOVERY: "Восстановление",
  MONITOR_FAILURE: "Сбой мониторинга", MONITOR_RECOVERY: "Мониторинг восстановлен", RETRY: "Повтор",
  ESCALATION: "Резервный номер", SYSTEM: "Система",
};
const MOCK_SCENARIO_LABELS: Record<NonNullable<MockPhoneGatewaySettings["scenario"]>, string> = {
  ACCEPTED: "Шлюз принял, результата пока нет",
  ANSWERED: "Абонент ответил",
  NOT_ANSWERED: "Абонент не ответил — перейти к резервному",
  FAILED: "Ошибка после принятия — перейти к резервному",
  UNAVAILABLE: "Шлюз недоступен — HTTP 503",
};

function Services({ token }: { token: string }) {
  const [items, setItems] = useState<ManagedService[]>([]);
  const [history, setHistory] = useState<ServiceEvent[]>([]);
  const [calls, setCalls] = useState<AlertCallAttempt[]>([]);
  const [alerts, setAlerts] = useState<AlertConfiguration | null>(null);
  const [mockGateway, setMockGateway] = useState<MockPhoneGatewaySettings | null>(null);
  const [mockScenario, setMockScenario] = useState("ANSWERED");
  const [mockDelayMs, setMockDelayMs] = useState(1000);
  const [pending, setPending] = useState<{ service: ManagedService; action: "START" | "STOP" | "RESTART" } | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const [serviceItems, serviceHistory, alertState, callHistory, mockState] = await Promise.all([
      api.admin.services(token), api.admin.serviceHistory(token), api.admin.alertConfiguration(token), api.admin.alertHistory(token),
      api.admin.mockAlertSettings(token),
    ]);
    setItems(serviceItems);
    setHistory(serviceHistory);
    setAlerts(alertState);
    setCalls(callHistory);
    setMockGateway(mockState);
    if (mockState.scenario) setMockScenario(mockState.scenario);
    if (mockState.delayMs != null) setMockDelayMs(mockState.delayMs);
  }, [token]);
  useEffect(() => { void run(load); }, [load, run]);
  useEffect(() => {
    if (!mockGateway?.available) return;
    const interval = window.setInterval(() => {
      void api.admin.alertHistory(token).then(setCalls).catch(() => undefined);
    }, 1500);
    return () => window.clearInterval(interval);
  }, [mockGateway?.available, token]);

  const execute = () => pending && run(async () => {
    await api.admin.serviceAction(token, pending.service.id, pending.action);
    setNotice(`${pending.service.label}: ${SERVICE_ACTION_LABELS[pending.action].toLowerCase()} — выполнено`);
    setPending(null);
    await load();
  });

  const testCall = () => run(async () => {
    try {
      const result = await api.admin.testAlert(token);
      setNotice(result.message);
    } finally {
      await load();
    }
  });

  const checkNow = () => run(async () => {
    setItems(await api.admin.checkServices(token));
    setNotice("Проверка сервисов завершена");
  });

  const saveMockScenario = () => run(async () => {
    const updated = await api.admin.updateMockAlertSettings(token, mockScenario, mockDelayMs);
    setMockGateway(updated);
    setNotice("Сценарий тестового звонка сохранён");
  });

  const retryCall = (id: string) => run(async () => {
    try {
      const result = await api.admin.retryAlert(token, id);
      setNotice(result.status === "FAILED" ? "Повторный звонок не запущен" : "Повторный звонок передан шлюзу");
    } finally {
      await load();
    }
  });

  return (
    <section className="panel-page">
      <div className="panel-head">
        <b>Управление сервисами</b>
        <small className="muted">остановленные модули не обрабатывают новые события, их данные сохраняются</small>
        <span className="spacer" />
        <button className="secondary" disabled={working} onClick={checkNow}>Проверить сейчас</button>
      </div>
      <div className={`service-alert-status ${alerts?.configured ? "configured" : "disabled"}`}>
        <div>
          <b>Аварийный звонок</b>
          <span>{alerts?.configured
            ? `телефонный шлюз настроен · номеров в цепочке: ${alerts.recipientCount}`
            : "не настроен — добавьте параметры шлюза на сервере"}</span>
          <details className="service-alert-setup" open={!alerts?.configured}>
            <summary>Как настроить</summary>
            <p>Исходный код менять не нужно. Администратор сервера добавляет в файл <code>/opt/arm112/.env</code> адрес внутреннего телефонного шлюза, его служебный токен и номер дежурного:</p>
            <code>ARM112_ALERT_CALL_GATEWAY_URL</code>
            <code>ARM112_ALERT_CALL_GATEWAY_TOKEN</code>
            <code>ARM112_ALERT_PHONE_NUMBERS</code>
            <code>ARM112_ALERT_PHONE_NUMBER</code>
            <p><code>ARM112_ALERT_PHONE_NUMBERS</code> содержит основной и резервные номера через запятую; одиночный параметр оставлен для совместимости.</p>
            <p>После изменения необходимо перезапустить backend. Секретный токен намеренно нельзя вводить или прочитать через браузер.</p>
          </details>
        </div>
        <button className="secondary" disabled={working || !alerts?.configured} onClick={testCall}>Проверить звонок</button>
      </div>
      {mockGateway?.available && (
        <div className="mock-phone-panel">
          <div>
            <b>Режим проверки звонков</b>
            <span>Только локальная среда: выберите, как mock-шлюз ответит на следующий звонок.</span>
          </div>
          <label>Результат
            <select value={mockScenario} onChange={(event) => setMockScenario(event.target.value)}>
              {mockGateway.allowedScenarios.map((scenario) => (
                <option key={scenario} value={scenario}>{MOCK_SCENARIO_LABELS[scenario]}</option>
              ))}
            </select>
          </label>
          <label>Задержка, мс
            <input type="number" min={100} max={30000} step={100} value={mockDelayMs}
              onChange={(event) => setMockDelayMs(Math.max(100, Math.min(30000, Number(event.target.value) || 100)))} />
          </label>
          <button className="secondary" disabled={working} onClick={saveMockScenario}>Применить</button>
        </div>
      )}
      <div className="managed-services">
        {items.map((service) => (
          <article className={`managed-service ${service.state.toLowerCase()} ${service.critical ? "critical" : ""}`} key={service.id}>
            <header>
              <div><b>{service.label}</b><p>{service.description}</p></div>
              <span className={`service-state ${service.state.toLowerCase()}`}>
                {service.state === "RUNNING" ? "работает" : service.state === "STOPPED" ? "остановлен" : service.state === "DEGRADED" ? "требует внимания" : "ошибка"}
              </span>
            </header>
            <dl>
              {service.metrics.map((metric) => <div key={metric.label}><dt>{metric.label}</dt><dd>{metric.value}</dd></div>)}
            </dl>
            <div className={`service-diagnostics ${service.issue ? "has-issue" : "healthy"}`}>
              <div><span>Последняя проверка</span><b>{dateTime(service.lastCheckedAt)}</b></div>
              <div><span>Последняя успешная</span><b>{service.lastSuccessfulAt ? dateTime(service.lastSuccessfulAt) : "ещё не было"}</b></div>
              <div><span>Ответ</span><b>{service.responseTimeMs == null ? "не измеряется" : `${service.responseTimeMs} мс`}</b></div>
              <p><b>{service.issue ? "Проблема:" : "Результат:"}</b> {service.issue ?? "проверка пройдена"}</p>
              <p><b>Что делать:</b> {service.recommendedAction}</p>
              <p><b>Зависит от:</b> {service.dependencies.length ? service.dependencies.join(", ") : "нет зависимостей"}</p>
            </div>
            <details className="service-help">
              <summary>Что это и когда перезапускать</summary>
              <p><b>Назначение:</b> {service.purpose}</p>
              <p><b>Если остановить:</b> {service.stopEffect}</p>
              <p><b>Перезапускать:</b> {service.restartWhen}</p>
            </details>
            <footer>
              {service.controllable ? service.allowedActions.map((action) => (
                <button key={action} className={action === "STOP" ? "danger-button" : action === "START" ? "primary-button" : "secondary"}
                  disabled={working} onClick={() => setPending({ service, action })}>
                  {SERVICE_ACTION_LABELS[action]}
                </button>
              )) : <small>Управляется через Docker/CI</small>}
            </footer>
          </article>
        ))}
      </div>
      <section className="service-history alert-call-history">
        <div className="panel-head">
          <b>История аварийных звонков</b>
          <small className="muted">номер показывается частично; ответ обновляет внутренний телефонный шлюз</small>
        </div>
        {calls.length === 0 ? <p className="empty-state">Звонков пока не было</p> : (
          <div className="table-scroll"><table className="data-table">
            <thead><tr><th>Время</th><th>Получатель</th><th>Причина</th><th>Сервис</th><th>Попытка</th><th>Статус</th><th>Ответ</th><th>Действие</th></tr></thead>
            <tbody>{calls.map((call) => (
              <tr key={call.id} title={call.errorMessage ?? call.message}>
                <td>{dateTime(call.requestedAt)}</td>
                <td>{call.recipient}</td>
                <td className="call-reason">
                  <b>{CALL_TRIGGER_LABELS[call.triggerType] ?? call.triggerType}</b>
                  <small>{call.message}</small>
                  {call.errorMessage && <small className="error-text">{call.errorMessage}</small>}
                </td>
                <td>{items.find((item) => item.id === call.serviceId)?.label ?? call.serviceId ?? "—"}</td>
                <td>{call.attemptNumber} · получатель {call.recipientOrder}</td>
                <td><span className={`call-status ${call.status.toLowerCase()}`}>{CALL_STATUS_LABELS[call.status]}</span></td>
                <td>{call.answered == null
                  ? call.status === "ACCEPTED" && call.gatewayCallId ? "ожидается" : "нет данных"
                  : call.answered ? "да" : "нет"}</td>
                <td><button className="ghost" disabled={working || !alerts?.configured} onClick={() => retryCall(call.id)}>повторить</button></td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      <section className="service-history">
        <div className="panel-head"><b>История сервисов</b><small className="muted">действия администраторов и автоматические изменения состояния</small></div>
        {history.length === 0 ? <p className="empty-state">Событий пока нет</p> : (
          <table className="data-table">
            <thead><tr><th>Время</th><th>Сервис</th><th>Событие</th><th>Состояние</th><th>Инициатор</th><th>Оповещение</th></tr></thead>
            <tbody>{history.map((event) => (
              <tr key={event.id}>
                <td>{dateTime(event.occurredAt)}</td>
                <td>{items.find((item) => item.id === event.serviceId)?.label ?? event.serviceId}</td>
                <td>{event.action ? SERVICE_ACTION_LABELS[event.action] : "Изменение состояния"}</td>
                <td><span className={`history-outcome ${event.outcome.toLowerCase()}`}>{event.currentState}</span></td>
                <td>{event.actorLogin ?? "система"}</td>
                <td>{event.notified ? "звонок запущен" : "—"}</td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </section>
      {pending && (
        <Modal title={`${SERVICE_ACTION_LABELS[pending.action]}: ${pending.service.label}`} onClose={() => setPending(null)}>
          <p className="dialog-copy">
            {pending.action === "STOP"
              ? "Модуль перестанет обрабатывать новые события. Текущие данные останутся в базе."
              : pending.action === "RESTART"
                ? "Модуль будет кратковременно остановлен и запущен снова."
                : "Модуль продолжит обработку накопленных и новых событий."}
          </p>
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setPending(null)}>Отмена</button>
            <button className={pending.action === "STOP" ? "call-button danger" : "primary"} disabled={working} onClick={execute}>
              {SERVICE_ACTION_LABELS[pending.action]}
            </button>
          </div>
        </Modal>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
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
  const [trainees, setTrainees] = useState<UserAdminView[]>([]);
  const [name, setName] = useState("");
  const [teacherId, setTeacherId] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [studentSearch, setStudentSearch] = useState("");
  const [editingGroup, setEditingGroup] = useState<Group | null>(null);
  const [editingIds, setEditingIds] = useState<string[]>([]);
  const [editingSearch, setEditingSearch] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);

  const load = useCallback(async () => {
    const [g, t, s] = await Promise.all([
      api.admin.groups(token), api.admin.users(token, "TEACHER"), api.admin.users(token, "TRAINEE"),
    ]);
    setGroups(g);
    setTeachers(t);
    setTrainees(s.filter((student) => student.active));
    setTeacherId((current) => current || t[0]?.id || "");
  }, [token]);
  useEffect(() => { void run(load); }, [load, run]);

  const create = () => run(async () => {
    const created = await api.admin.createGroup(token, name.trim(), teacherId);
    if (selectedIds.length) await api.admin.setGroupMembers(token, created.id, selectedIds);
    setName("");
    setSelectedIds([]);
    setStudentSearch("");
    setNotice(selectedIds.length ? "Группа создана, обучающиеся назначены" : "Группа создана");
    await load();
  });
  const reassign = (g: Group, next: string) => run(async () => { await api.admin.updateGroup(token, g.id, g.name, next); await load(); });
  const openMembers = (group: Group) => {
    setEditingGroup(group);
    setEditingIds(group.members.map((member) => member.id));
    setEditingSearch("");
  };
  const saveMembers = () => editingGroup && run(async () => {
    await api.admin.setGroupMembers(token, editingGroup.id, editingIds);
    setEditingGroup(null);
    setNotice("Состав группы сохранён");
    await load();
  });

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Группы</b></div>
      <div className="form-grid group-create-form">
        <label><span>Название</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label><span>Преподаватель</span>
          <select value={teacherId} onChange={(e) => setTeacherId(e.target.value)}>
            {teachers.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}
          </select></label>
        <div className="group-students-field">
          <span>Обучающиеся ({selectedIds.length} выбрано)</span>
          <StudentPicker students={trainees} groups={groups} selected={selectedIds} search={studentSearch}
            onSearch={setStudentSearch} onChange={setSelectedIds} />
        </div>
        <div className="dialog-actions"><button className="primary" disabled={working || !name.trim() || !teacherId} onClick={create}>Создать</button></div>
      </div>
      <table className="data-table">
        <thead><tr><th>Группа</th><th>Преподаватель</th><th>Обучающиеся</th><th /></tr></thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g.id}>
              <td><b>{g.name}</b></td>
              <td>
                <select value={g.teacherId} onChange={(e) => reassign(g, e.target.value)}>
                  {teachers.map((t) => <option key={t.id} value={t.id}>{t.displayName}</option>)}
                </select>
              </td>
              <td>{g.members.map((m) => `${m.displayName} (АРМ ${m.workstationNumber ?? "—"})`).join(", ") || <span className="muted">пока никого нет</span>}</td>
              <td><button className="ghost" onClick={() => openMembers(g)}>изменить состав</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {editingGroup && (
        <Modal title={`Состав группы «${editingGroup.name}»`} wide onClose={() => setEditingGroup(null)}>
          <StudentPicker students={trainees} groups={groups} selected={editingIds} search={editingSearch}
            onSearch={setEditingSearch} onChange={setEditingIds} />
          <div className="dialog-actions">
            <button className="secondary" onClick={() => setEditingGroup(null)}>Отмена</button>
            <button className="primary" disabled={working} onClick={saveMembers}>Сохранить состав</button>
          </div>
        </Modal>
      )}
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}

function StudentPicker({ students, groups, selected, search, onSearch, onChange }: {
  students: UserAdminView[];
  groups: Group[];
  selected: string[];
  search: string;
  onSearch: (value: string) => void;
  onChange: (ids: string[]) => void;
}) {
  const query = search.trim().toLowerCase();
  const visible = students.filter((student) => !query || [student.displayName, student.login, student.workstationNumber]
    .some((value) => value?.toLowerCase().includes(query)));
  const toggle = (id: string) => onChange(selected.includes(id)
    ? selected.filter((current) => current !== id)
    : [...selected, id]);
  return (
    <div className="student-picker">
      <input className="student-search" value={search} onChange={(event) => onSearch(event.target.value)}
        placeholder="Поиск по ФИО, логину или номеру АРМ" />
      <div className="student-options">
        {visible.map((student) => {
          const currentGroup = groups.find((group) => group.id === student.groupId);
          return (
            <label key={student.id} className={selected.includes(student.id) ? "selected" : ""}>
              <input type="checkbox" checked={selected.includes(student.id)} onChange={() => toggle(student.id)} />
              <span><b>{student.displayName}</b><small>{student.login} · АРМ {student.workstationNumber ?? "не указан"}{currentGroup ? ` · сейчас: ${currentGroup.name}` : " · без группы"}</small></span>
            </label>
          );
        })}
        {!visible.length && <p className="empty-state">Ничего не найдено</p>}
      </div>
    </div>
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
        {Object.entries(values).filter(([key]) => !key.startsWith("service.")).map(([key, value]) => (
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
        <label className="audit-filter"><span>Роль</span><select value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="">все роли</option>
          {(["ADMIN", "TEACHER", "TRAINEE"] as Role[]).map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
        </select></label>
        <label className="audit-filter audit-action-filter"><span>Действие или адрес API</span>
          <input value={action} onChange={(e) => setAction(e.target.value)} placeholder="Например: /cards, /users или /settings" />
        </label>
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
