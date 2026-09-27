"use client";

import type { ReactNode } from "react";
import { countdown, dateTime, statusLabels, timeOnly } from "../../../lib/format";

/** Строка журнала происшествий — общая для экрана ДДС (карточки) и оператора 112 (сохранённые и фоновые карточки). */
export type JournalRowView = {
  id: string;
  number: string;
  receivedAt: string;
  workstationNumber: string;
  incidentTypeLabel: string;
  addressLabel: string;
  description: string;
  senderLabel: string;
  statusLabel: string;
  overdue?: boolean;
  deadline?: string | null;
  foreign?: boolean;
};

type Props = {
  rows: JournalRowView[];
  search: string;
  setSearch: (value: string) => void;
  onOpen: (id: string) => void;
  now: number;
  workstationLabel: string;
  loading: boolean;
  /** Правая часть заголовка списка: счётчики очереди, кнопка завершения. */
  extra?: ReactNode;
  /**
   * Расширенный поиск. Необязателен: экран оператора 112 обходится строкой поиска,
   * у диспетчера ДДС карточек в журнале больше и без отбора по типу и статусу их не найти.
   */
  advancedOpen?: boolean;
  setAdvancedOpen?: (value: boolean) => void;
  filterType?: string;
  setFilterType?: (value: string) => void;
  filterStatus?: string;
  setFilterStatus?: (value: string) => void;
  filterAddress?: string;
  setFilterAddress?: (value: string) => void;
  onReset?: () => void;
};

/** Главный экран АРМ по кадрам dds-02.png / dds-03.png: поиск, часы, «Список происшествий». */
export function IncidentJournal({ rows, search, setSearch, onOpen, now, workstationLabel, loading, extra,
                                 advancedOpen, setAdvancedOpen, filterType, setFilterType,
                                 filterStatus, setFilterStatus, filterAddress, setFilterAddress,
                                 onReset }: Props) {
  const current = new Date(now);
  const advanced = Boolean(setAdvancedOpen);
  return (
    <section className="journal">
      <div className="journal-heading">
        <div className="journal-search">
          <div className="search-line">
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск происшествий" aria-label="Поиск происшествий" />
            <span className="magnifier" aria-hidden="true">⌕</span>
          </div>
          <div className="search-meta">
            {advanced ? (
              <button type="button" className="linkish" onClick={() => setAdvancedOpen?.(!advancedOpen)}>
                расширенный по параметрам{advancedOpen ? "⌃" : "⌄"}
              </button>
            ) : <small>расширенный по параметрам⌄</small>}
            <button onClick={() => (onReset ? onReset() : setSearch(""))}>сбросить</button>
          </div>
          {advanced && advancedOpen && (
            <div className="advanced-search">
              <label><span>Тип</span>
                <input value={filterType ?? ""} onChange={(e) => setFilterType?.(e.target.value)} placeholder="пожар, ДТП…" /></label>
              <label><span>Статус</span>
                <select value={filterStatus ?? ""} onChange={(e) => setFilterStatus?.(e.target.value)}>
                  <option value="">все</option>
                  {Object.entries(statusLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
                </select>
              </label>
              <label><span>Адрес</span>
                <input value={filterAddress ?? ""} onChange={(e) => setFilterAddress?.(e.target.value)} placeholder="улица, округ…" /></label>
            </div>
          )}
        </div>
        <div className="digital-clock">
          <b>{new Intl.DateTimeFormat("ru-RU", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).format(current)}</b>
          <strong>{new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit" }).format(current)}</strong>
          <span>:{String(current.getSeconds()).padStart(2, "0")}</span>
          <small>{workstationLabel}</small>
        </div>
      </div>
      <div className="list-area">
        <div className="list-title">
          <b>Список происшествий⌃</b>
          <span>
            {extra}
            ● уведомления　 <select aria-label="Фильтр"><option>выберите что показать</option></select>
          </span>
        </div>
        <div className="incident-columns"><span>Связи</span><span>ЧС</span><span>Опер.</span><span>АРМ</span><span>Номер</span><span>Дата</span><span>Время</span><span>Тип происшествия</span><span>Постр.</span><span>Адрес</span><span>Статус службы</span></div>
        {rows.length === 0 ? (
          <div className="empty-list">{loading ? "Обновление…" : "Карточки не найдены"}</div>
        ) : rows.map((item) => (
          <button key={item.id} className={`incident-row ${item.overdue ? "overdue" : ""} ${item.foreign ? "foreign" : ""}`} onClick={() => onOpen(item.id)}>
            <span>⌄　◆　⚡</span><span>0</span><span>0</span><span>{item.workstationNumber}</span><b>{item.number.replace(/\D/g, "").slice(-8)}</b>
            <span>{new Date(item.receivedAt).toLocaleDateString("ru-RU")}</span><strong>{timeOnly(item.receivedAt)}</strong>
            <b title={item.incidentTypeLabel}>{item.incidentTypeLabel}</b>
            <span>Нет</span>
            <b title={item.addressLabel}>{item.addressLabel}</b>
            <span title={item.statusLabel}>{item.statusLabel}</span>
            <em title={item.description}>
              <span className="descr-label">Описание:</span>
              <span className="descr-meta">{dateTime(item.receivedAt)} {item.senderLabel}　-</span>
              <span className="descr-text">{item.description}</span>
            </em>
            {item.deadline && <i>{countdown(item.deadline, now)}</i>}
          </button>
        ))}
        <div className="pagination">Страница: 1　 Записей на странице: {Math.max(10, rows.length)}　 <b>1–{rows.length} из {rows.length}</b>　‹　›</div>
      </div>
    </section>
  );
}
