import { useEffect, useMemo, useState } from 'react';
import { fetchDevices } from '../api';
import type { Device, WSEnvelope } from '../types';
import { Smartphone, Wifi, WifiOff, Copy, Check, Search } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';
import { ru } from 'date-fns/locale';
import Avatar from '../components/Avatar';

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  useEffect(() => {
    fetchDevices()
      .then(setDevices)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    function handler(e: Event) {
      const env = (e as CustomEvent<WSEnvelope>).detail;
      if (env.type === 'device_status') {
        const data = env.payload as { device_id: string; status: string };
        setDevices((prev) =>
          prev.map((d) => (d.id === data.device_id ? { ...d, status: data.status } : d)),
        );
      }
    }
    window.addEventListener('crm-ws', handler);
    return () => window.removeEventListener('crm-ws', handler);
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return devices;
    return devices.filter((d) =>
      [d.name, d.phone_number, d.id].some((v) => (v || '').toLowerCase().includes(q)),
    );
  }, [devices, query]);

  const stats = useMemo(() => {
    const online = devices.filter((d) => d.status === 'online').length;
    return { total: devices.length, online, offline: devices.length - online };
  }, [devices]);

  async function copy(text: string, id: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 1500);
    } catch { /* ignore */ }
  }

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="mx-auto max-w-6xl px-8 py-8">
        {/* Header */}
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Устройства</h1>
            <p className="mt-1 text-sm text-gray-500">
              Управляйте Android-устройствами, подключёнными к CRM
            </p>
          </div>
          <div className="relative w-full max-w-xs">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Поиск…"
              className="w-full rounded-lg border border-gray-200 bg-white py-2 pl-9 pr-3 text-sm transition focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
            />
          </div>
        </div>

        {/* Stats */}
        <div className="mb-6 grid gap-3 sm:grid-cols-3">
          <StatCard label="Всего" value={stats.total} accent="from-gray-700 to-gray-900" />
          <StatCard label="Онлайн" value={stats.online} accent="from-emerald-500 to-teal-600" />
          <StatCard label="Офлайн" value={stats.offline} accent="from-gray-400 to-gray-500" />
        </div>

        {/* List */}
        {loading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="h-36 animate-pulse rounded-2xl bg-white ring-1 ring-gray-100" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-2xl bg-white px-8 py-16 text-center shadow-sm ring-1 ring-gray-100">
            <Smartphone size={44} className="mx-auto mb-4 text-gray-300" />
            <p className="text-base font-semibold text-gray-700">
              {query ? 'Ничего не найдено' : 'Нет зарегистрированных устройств'}
            </p>
            {!query && (
              <p className="mx-auto mt-2 max-w-md text-sm text-gray-500">
                Зарегистрируйте устройство через API:{' '}
                <code className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-xs text-gray-700">
                  POST /api/v1/devices/register
                </code>
              </p>
            )}
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {filtered.map((d) => {
              const online = d.status === 'online';
              return (
                <div
                  key={d.id}
                  className="group overflow-hidden rounded-2xl bg-white p-5 shadow-sm ring-1 ring-gray-100 transition hover:shadow-md"
                >
                  <div className="mb-4 flex items-start gap-3">
                    <Avatar name={d.name} size={44} online={online} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-semibold text-gray-900">{d.name}</p>
                      <p className="truncate text-sm text-gray-500">
                        {d.phone_number || 'Без номера'}
                      </p>
                    </div>
                  </div>

                  <div className="mb-3 flex items-center gap-2">
                    {online ? (
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 ring-1 ring-emerald-100">
                        <Wifi size={12} /> Онлайн
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-gray-50 px-2.5 py-1 text-xs font-medium text-gray-600 ring-1 ring-gray-200">
                        <WifiOff size={12} /> Офлайн
                      </span>
                    )}
                    {d.last_seen_at && !online && (
                      <span className="text-xs text-gray-400">
                        {formatDistanceToNow(new Date(d.last_seen_at), {
                          addSuffix: true,
                          locale: ru,
                        })}
                      </span>
                    )}
                  </div>

                  <button
                    onClick={() => copy(d.id, d.id)}
                    className="flex w-full items-center justify-between rounded-lg bg-gray-50 px-3 py-2 text-xs text-gray-500 transition hover:bg-gray-100"
                    title="Копировать ID"
                  >
                    <span className="truncate font-mono">{d.id}</span>
                    {copiedId === d.id ? (
                      <Check size={14} className="ml-2 shrink-0 text-emerald-500" />
                    ) : (
                      <Copy size={14} className="ml-2 shrink-0 opacity-0 transition group-hover:opacity-100" />
                    )}
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({ label, value, accent }: { label: string; value: number; accent: string }) {
  return (
    <div className="overflow-hidden rounded-2xl bg-white p-5 shadow-sm ring-1 ring-gray-100">
      <div className="flex items-center gap-4">
        <div className={`h-12 w-1.5 rounded-full bg-gradient-to-b ${accent}`} />
        <div>
          <p className="text-xs font-medium uppercase tracking-wider text-gray-500">{label}</p>
          <p className="mt-1 text-2xl font-bold text-gray-900">{value}</p>
        </div>
      </div>
    </div>
  );
}
