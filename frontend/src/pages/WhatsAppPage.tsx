import { useEffect, useState, useCallback } from 'react';
import { fetchBaileysStatus, fetchBaileysQR, baileysLogout } from '../api';
import type { BaileysStatus } from '../api';
import { Wifi, WifiOff, QrCode, LogOut, RefreshCw, Loader2 } from 'lucide-react';

export default function WhatsAppPage() {
  const [status, setStatus] = useState<BaileysStatus | null>(null);
  const [qrImage, setQrImage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadStatus = useCallback(async () => {
    try {
      const s = await fetchBaileysStatus();
      setStatus(s);
      setError(null);
      return s;
    } catch (e) {
      setError('Сервис Baileys недоступен');
      setStatus(null);
      return null;
    }
  }, []);

  const loadQR = useCallback(async () => {
    try {
      const data = await fetchBaileysQR();
      if (data.qr) {
        setQrImage(data.qr);
      } else {
        setQrImage(null);
      }
    } catch {
      setQrImage(null);
    }
  }, []);

  // Initial load
  useEffect(() => {
    loadStatus().finally(() => setLoading(false));
  }, [loadStatus]);

  // Poll status every 3s, QR every 4s when connecting
  useEffect(() => {
    const statusInterval = setInterval(async () => {
      const s = await loadStatus();
      if (s && s.status === 'connecting' && s.hasQR) {
        loadQR();
      } else if (s && s.status === 'connected') {
        setQrImage(null);
      }
    }, 3000);

    return () => clearInterval(statusInterval);
  }, [loadStatus, loadQR]);

  // Load QR immediately when status changes to connecting
  useEffect(() => {
    if (status?.status === 'connecting' && status.hasQR) {
      loadQR();
    }
  }, [status, loadQR]);

  async function handleLogout() {
    try {
      await baileysLogout();
      setStatus({ status: 'disconnected', hasQR: false });
      setQrImage(null);
    } catch {
      setError('Не удалось отключиться');
    }
  }

  async function handleRefresh() {
    setLoading(true);
    await loadStatus();
    setLoading(false);
  }

  const isConnected = status?.status === 'connected';
  const isConnecting = status?.status === 'connecting';

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="mx-auto max-w-2xl px-8 py-8">
        {/* Header */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-gray-900">WhatsApp Web</h1>
          <p className="mt-1 text-sm text-gray-500">
            Подключение через WhatsApp Web протокол для отправки сообщений без открытия WhatsApp на телефоне
          </p>
        </div>

        {/* Status card */}
        <div className="mb-6 overflow-hidden rounded-2xl bg-white p-6 shadow-sm ring-1 ring-gray-100">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {isConnected ? (
                <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-emerald-50">
                  <Wifi className="text-emerald-600" size={24} />
                </div>
              ) : (
                <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-gray-100">
                  <WifiOff className="text-gray-400" size={24} />
                </div>
              )}
              <div>
                <p className="font-semibold text-gray-900">
                  {isConnected
                    ? 'Подключено'
                    : isConnecting
                      ? 'Ожидание сканирования QR...'
                      : error
                        ? 'Сервис недоступен'
                        : 'Отключено'}
                </p>
                <p className="text-sm text-gray-500">
                  {isConnected
                    ? 'Сообщения отправляются через WhatsApp Web'
                    : isConnecting
                      ? 'Отсканируйте QR-код в WhatsApp на телефоне'
                      : 'Подключите WhatsApp для тихой отправки'}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <button
                onClick={handleRefresh}
                disabled={loading}
                className="rounded-lg p-2 text-gray-400 transition hover:bg-gray-100 hover:text-gray-600 disabled:opacity-50"
                title="Обновить"
              >
                <RefreshCw size={18} className={loading ? 'animate-spin' : ''} />
              </button>
              {isConnected && (
                <button
                  onClick={handleLogout}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-600 transition hover:bg-red-100"
                >
                  <LogOut size={14} />
                  Отключить
                </button>
              )}
            </div>
          </div>
        </div>

        {/* QR Code */}
        {isConnecting && (
          <div className="overflow-hidden rounded-2xl bg-white p-8 shadow-sm ring-1 ring-gray-100">
            <div className="flex flex-col items-center">
              {qrImage ? (
                <>
                  <img
                    src={qrImage}
                    alt="WhatsApp QR Code"
                    className="mb-4 h-72 w-72 rounded-xl"
                  />
                  <p className="text-center text-sm text-gray-600">
                    Откройте WhatsApp → Настройки → Связанные устройства → Привязать устройство
                  </p>
                </>
              ) : (
                <div className="flex flex-col items-center py-8">
                  <Loader2 size={40} className="mb-4 animate-spin text-emerald-500" />
                  <p className="text-sm text-gray-500">Генерация QR-кода...</p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Connected info */}
        {isConnected && (
          <div className="overflow-hidden rounded-2xl bg-emerald-50 p-6 ring-1 ring-emerald-100">
            <div className="flex items-start gap-3">
              <QrCode size={20} className="mt-0.5 text-emerald-600" />
              <div>
                <p className="font-medium text-emerald-800">Как это работает</p>
                <ul className="mt-2 space-y-1 text-sm text-emerald-700">
                  <li>• Сообщения отправляются напрямую через WhatsApp Web — без открытия приложения на телефоне</li>
                  <li>• Если Baileys недоступен, система автоматически переключится на агент (RemoteInput / A11y)</li>
                  <li>• Сессия сохраняется — QR-код нужно сканировать только один раз</li>
                </ul>
              </div>
            </div>
          </div>
        )}

        {/* Error */}
        {error && !isConnecting && !isConnected && (
          <div className="overflow-hidden rounded-2xl bg-amber-50 p-6 ring-1 ring-amber-100">
            <p className="text-sm text-amber-800">{error}</p>
            <p className="mt-1 text-xs text-amber-600">
              Убедитесь что сервис baileys запущен: <code className="rounded bg-amber-100 px-1">docker compose up baileys</code>
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
