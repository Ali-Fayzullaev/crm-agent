export interface Device {
  id: string;
  name: string;
  phone_number: string;
  status: string;
  last_seen_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface Conversation {
  id: string;
  device_id: string;
  contact_id: string;
  assigned_operator_id: string | null;
  status: string;
  unread_count: number;
  last_message_at: string | null;
  last_message_text: string;
  created_at: string;
  updated_at: string;
  contact_name: string;
  contact_phone: string;
  device_name: string;
}

export interface Message {
  id: string;
  conversation_id: string;
  device_id: string;
  external_id: string;
  direction: 'in' | 'out';
  content: string;
  content_type: string;
  status: string;
  operator_id: string | null;
  created_at: string;
  sent_at: string | null;
  delivered_at: string | null;
  read_at: string | null;
}

export interface Operator {
  id: string;
  email: string;
  name: string;
  role: string;
  created_at: string;
}

export interface WSEnvelope {
  type: string;
  payload: unknown;
}

export interface LoginResponse {
  token: string;
  operator: Operator;
}
