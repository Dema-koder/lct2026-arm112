"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, type Group, type Material } from "../../../lib/api";
import { bytes, dateTime } from "../../../lib/format";
import { ErrorBanner, Notice, useAction, useNotice } from "../common";

/** Методические материалы: загрузка файла и назначение группам. */
export function Materials({ token }: { token: string }) {
  const [items, setItems] = useState<Material[]>([]);
  const [groups, setGroups] = useState<Group[]>([]);
  const [title, setTitle] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useNotice();
  const [working, run] = useAction(setError);
  const file = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    const [m, g] = await Promise.all([api.teacher.materials(token), api.teacher.groups(token)]);
    setItems(m);
    setGroups(g);
    if (chosen.length === 0 && g[0]) setChosen([g[0].id]);
  }, [token, chosen.length]);
  useEffect(() => { void run(load); }, [load, run]);

  const upload = () => run(async () => {
    const selected = file.current?.files?.[0];
    if (!selected) { setError("Выберите файл"); return; }
    await api.teacher.uploadMaterial(token, selected, title || selected.name, chosen);
    setTitle("");
    if (file.current) file.current.value = "";
    setNotice("Материал загружен");
    await load();
  });
  const remove = (id: string) => run(async () => { await api.teacher.deleteMaterial(token, id); await load(); });

  return (
    <section className="panel-page">
      <div className="panel-head"><b>Методические материалы</b></div>
      <div className="form-grid">
        <label><span>Файл (DOCX, PDF, XLSX)</span><input type="file" ref={file} /></label>
        <label><span>Название</span><input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="как имя файла, если пусто" /></label>
        <label className="wide"><span>Группы</span>
          <div className="type-chips">
            {groups.map((g) => (
              <button type="button" key={g.id} className={`chip ${chosen.includes(g.id) ? "selected" : ""}`}
                onClick={() => setChosen(chosen.includes(g.id) ? chosen.filter((x) => x !== g.id) : [...chosen, g.id])}>{g.name}</button>
            ))}
          </div></label>
        <div className="dialog-actions"><button className="primary" disabled={working} onClick={upload}>Загрузить</button></div>
      </div>
      <table className="data-table">
        <thead><tr><th>Название</th><th>Файл</th><th>Размер</th><th>Загружен</th><th>Группы</th><th /></tr></thead>
        <tbody>
          {items.length === 0 && <tr><td colSpan={6} className="muted">Материалов пока нет</td></tr>}
          {items.map((m) => (
            <tr key={m.id}>
              <td><b>{m.title}</b></td><td>{m.fileName}</td><td>{bytes(m.sizeBytes)}</td><td>{dateTime(m.uploadedAt)}</td>
              <td>{m.groupIds.map((id) => groups.find((g) => g.id === id)?.name ?? "…").join(", ") || "—"}</td>
              <td><button className="ghost" onClick={() => remove(m.id)}>удалить</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <ErrorBanner error={error} onClose={() => setError("")} />
      <Notice text={notice} />
    </section>
  );
}
