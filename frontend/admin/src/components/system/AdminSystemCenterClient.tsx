"use client";

import { useState } from "react";
import { ApiRequestError } from "@/modules/api/common";
import { upsertSiteSetting } from "@/modules/settings/browser-api";
import type { SiteSettingResponse } from "@/modules/settings/types";

const settingValueTypes = ["STRING", "NUMBER", "BOOLEAN", "JSON"] as const;

function extractError(error: unknown, fallback: string) {
  if (error instanceof ApiRequestError) {
    const payload = error.payload as { message?: string } | null;
    return payload?.message ?? fallback;
  }
  return fallback;
}

export function AdminSystemCenterClient({ initialSettings }: { initialSettings: SiteSettingResponse[] }) {
  const [settings, setSettings] = useState(initialSettings);
  const [message, setMessage] = useState<string | null>(null);
  const [savingKey, setSavingKey] = useState<string | null>(null);

  async function handleSettingSave(setting: SiteSettingResponse) {
    try {
      setSavingKey(setting.id);
      setMessage(null);
      const updated = await upsertSiteSetting(setting.settingKey, {
        settingValue: setting.settingValue,
        valueType: setting.valueType,
        description: setting.description,
        isPublic: setting.isPublic
      });
      setSettings((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setMessage(`Đã lưu cấu hình ${updated.settingKey}.`);
    } catch (error) {
      setMessage(extractError(error, "Không lưu được cấu hình hệ thống"));
    } finally {
      setSavingKey(null);
    }
  }

  return (
    <section className="card panel">
      <div className="panel-header">
        <div>
          <h2>Cấu hình hệ thống</h2>
          <p className="panel-copy">Chỉ hiển thị dữ liệu đang được V2 lưu trong bảng site_settings.</p>
        </div>
      </div>
      {message ? <p className="action-message">{message}</p> : null}
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr><th>Key</th><th>Giá trị</th><th>Kiểu</th><th>Mô tả</th><th>Public</th><th>Lưu</th></tr>
          </thead>
          <tbody>
            {settings.length === 0 ? <tr><td colSpan={6} className="table-empty">Chưa có cấu hình nào trong hệ thống.</td></tr> : null}
            {settings.map((item) => (
              <tr key={item.id}>
                <td><strong>{item.settingKey}</strong></td>
                <td><input className="admin-input" value={item.settingValue ?? ""} onChange={(event) => setSettings((current) => current.map((setting) => setting.id === item.id ? { ...setting, settingValue: event.target.value } : setting))} /></td>
                <td>
                  <select className="select" value={item.valueType} onChange={(event) => setSettings((current) => current.map((setting) => setting.id === item.id ? { ...setting, valueType: event.target.value } : setting))}>
                    {settingValueTypes.map((type) => <option value={type} key={type}>{type}</option>)}
                  </select>
                </td>
                <td><input className="admin-input" value={item.description ?? ""} onChange={(event) => setSettings((current) => current.map((setting) => setting.id === item.id ? { ...setting, description: event.target.value } : setting))} /></td>
                <td><label className="admin-check"><input type="checkbox" checked={item.isPublic} onChange={(event) => setSettings((current) => current.map((setting) => setting.id === item.id ? { ...setting, isPublic: event.target.checked } : setting))} /> Public</label></td>
                <td><button className="admin-btn" type="button" onClick={() => void handleSettingSave(item)} disabled={savingKey === item.id}>{savingKey === item.id ? "Đang lưu..." : "Lưu"}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
