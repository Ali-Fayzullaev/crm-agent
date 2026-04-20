import type { Conversation, Device, LoginResponse, Message } from './types';

const TOKEN_KEY = 'crm_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string>),
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(path, { ...init, headers });

  if (res.status === 401) {
    clearToken();
    window.location.href = '/login';
    throw new Error('Unauthorized');
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { error?: string }).error || res.statusText);
  }

  return res.json() as Promise<T>;
}

// ── Auth ────────────────────────────────────────────────────

export async function login(email: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

// ── Devices ─────────────────────────────────────────────────

export async function fetchDevices(): Promise<Device[]> {
  const data = await request<{ devices: Device[] | null }>('/api/v1/devices');
  return data.devices ?? [];
}

// ── Conversations ───────────────────────────────────────────

export async function fetchConversations(
  status?: string,
  limit = 50,
  offset = 0,
): Promise<Conversation[]> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (status) params.set('status', status);
  const data = await request<{ conversations: Conversation[] | null }>(
    `/api/v1/conversations?${params}`,
  );
  return data.conversations ?? [];
}

export async function fetchConversation(id: string): Promise<Conversation> {
  return request<Conversation>(`/api/v1/conversations/${id}`);
}

export async function updateConversation(
  id: string,
  body: { status?: string; assigned_operator_id?: string },
): Promise<void> {
  await request(`/api/v1/conversations/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

// ── Messages ────────────────────────────────────────────────

export async function fetchMessages(
  conversationId: string,
  limit = 100,
  offset = 0,
): Promise<Message[]> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  const data = await request<{ messages: Message[] | null }>(
    `/api/v1/conversations/${conversationId}/messages?${params}`,
  );
  return data.messages ?? [];
}

export async function sendReply(conversationId: string, content: string): Promise<Message> {
  const data = await request<{ message: Message }>(
    `/api/v1/conversations/${conversationId}/reply`,
    { method: 'POST', body: JSON.stringify({ content }) },
  );
  return data.message;
}
