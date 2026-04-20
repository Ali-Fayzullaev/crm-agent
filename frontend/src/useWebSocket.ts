import { useEffect, useRef, useCallback, useState } from 'react';
import { getToken } from './api';
import type { WSEnvelope } from './types';

type WSHandler = (envelope: WSEnvelope) => void;

export function useWebSocket(onMessage: WSHandler) {
  const wsRef = useRef<WebSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout>>();
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  const connect = useCallback(() => {
    const token = getToken();
    if (!token) return;

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${window.location.host}/ws/crm?token=${token}`);

    ws.onopen = () => setConnected(true);

    ws.onmessage = (e) => {
      try {
        const envelope = JSON.parse(e.data) as WSEnvelope;
        onMessageRef.current(envelope);
      } catch { /* ignore bad frames */ }
    };

    ws.onclose = () => {
      setConnected(false);
      reconnectTimer.current = setTimeout(connect, 3000);
    };

    ws.onerror = () => ws.close();

    wsRef.current = ws;
  }, []);

  useEffect(() => {
    connect();
    return () => {
      clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
    };
  }, [connect]);

  return { connected };
}
