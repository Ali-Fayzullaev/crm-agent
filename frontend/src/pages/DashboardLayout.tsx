import { useEffect, useState, useCallback } from 'react';
import { Outlet, useNavigate, Link, useLocation } from 'react-router-dom';
import { clearToken, getToken, fetchDevices } from '../api';
import { useWebSocket } from '../useWebSocket';
import { MessageSquare, Smartphone, LogOut, Wifi, WifiOff } from 'lucide-react';
import type { Device, WSEnvelope } from '../types';

export default function DashboardLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [devices, setDevices] = useState<Device[]>([]);

  const handleWS = useCallback((env: WSEnvelope) => {
    window.dispatchEvent(new CustomEvent('crm-ws', { detail: env }));
  }, []);

  const { connected } = useWebSocket(handleWS);

  useEffect(() => {
    if (!getToken()) {
      navigate('/login', { replace: true });
      return;
    }
    fetchDevices().then(setDevices).catch(() => {});
  }, [navigate]);

  // Listen for device status WS events to update sidebar counters
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

  function handleLogout() {
    clearToken();
    navigate('/login', { replace: true });
  }

  const onlineDevices = devices.filter((d) => d.status === 'online').length;

  const navItems = [
    { to: '/', icon: MessageSquare, label: 'Чаты' },
    { to: '/devices', icon: Smartphone, label: 'Устройства', badge: devices.length || undefined },
  ];

  return (
    <div className="flex h-screen bg-gray-100">
      {/* Sidebar */}
      <aside className="flex w-20 flex-col items-center bg-gradient-to-b from-gray-900 to-gray-800 py-5 text-gray-400">
        <Link to="/" className="mb-8 flex h-11 w-11 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-500 to-teal-600 text-white shadow-lg shadow-emerald-500/30">
          <MessageSquare size={20} />
        </Link>

        <nav className="flex flex-1 flex-col gap-2">
          {navItems.map((item) => {
            const active =
              item.to === '/'
                ? location.pathname === '/' || location.pathname.startsWith('/chat')
                : location.pathname.startsWith(item.to);
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`group relative flex h-11 w-11 items-center justify-center rounded-xl transition ${
                  active
                    ? 'bg-emerald-500/15 text-emerald-400 ring-1 ring-emerald-500/30'
                    : 'hover:bg-white/5 hover:text-white'
                }`}
                title={item.label}
              >
                <item.icon size={20} />
                {item.badge !== undefined && item.badge > 0 && (
                  <span className="absolute -right-1 -top-1 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-emerald-500 px-1 text-[10px] font-bold text-white">
                    {item.badge}
                  </span>
                )}
                <span className="pointer-events-none absolute left-full ml-3 hidden whitespace-nowrap rounded-md bg-gray-900 px-2 py-1 text-xs text-white shadow-lg group-hover:block">
                  {item.label}
                </span>
              </Link>
            );
          })}
        </nav>

        <div className="flex flex-col items-center gap-3">
          <div
            className={`flex h-9 w-9 items-center justify-center rounded-lg ${
              connected
                ? 'bg-emerald-500/15 text-emerald-400'
                : 'bg-red-500/15 text-red-400'
            }`}
            title={connected ? 'WebSocket подключен' : 'Нет соединения'}
          >
            {connected ? <Wifi size={16} /> : <WifiOff size={16} />}
          </div>
          <div
            className="flex h-9 w-9 items-center justify-center rounded-lg bg-white/5 text-xs font-bold text-gray-300"
            title={`Устройств онлайн: ${onlineDevices} / ${devices.length}`}
          >
            {onlineDevices}/{devices.length}
          </div>
          <button
            onClick={handleLogout}
            className="flex h-9 w-9 items-center justify-center rounded-lg text-gray-400 transition hover:bg-white/5 hover:text-white"
            title="Р’С‹Р№С‚Рё"
          >
            <LogOut size={18} />
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex flex-1 overflow-hidden">
        <Outlet context={{ devices }} />
      </main>
    </div>
  );
}
