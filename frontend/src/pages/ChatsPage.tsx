import { useEffect, useMemo, useState, useRef, type FormEvent } from 'react';
import { useParams, Link } from 'react-router-dom';
import { fetchConversations, fetchMessages, sendReply, fetchConversation } from '../api';
import type { Conversation, Message, WSEnvelope } from '../types';
import {
  Send,
  ArrowLeft,
  Check,
  CheckCheck,
  Search,
  MessageSquare,
  Loader2,
  Phone,
} from 'lucide-react';
import Avatar from '../components/Avatar';
import { formatShortTime } from '../utils';

export default function ChatsPage() {
  const { conversationId } = useParams<{ conversationId: string }>();
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [activeConv, setActiveConv] = useState<Conversation | null>(null);
  const [reply, setReply] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingConvs, setLoadingConvs] = useState(true);
  const [loadingMsgs, setLoadingMsgs] = useState(false);
  const [query, setQuery] = useState('');
  const messagesEnd = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setLoadingConvs(true);
    fetchConversations()
      .then(setConversations)
      .catch(() => {})
      .finally(() => setLoadingConvs(false));
  }, []);

  useEffect(() => {
    if (!conversationId) {
      setMessages([]);
      setActiveConv(null);
      return;
    }

    // Optimistic clear to avoid stale unread badge while data is loading.
    setConversations((prev) =>
      prev.map((c) => (c.id === conversationId ? { ...c, unread_count: 0 } : c)),
    );

    setLoadingMsgs(true);
    Promise.all([
      fetchMessages(conversationId).then(setMessages).catch(() => {}),
      fetchConversation(conversationId).then(setActiveConv).catch(() => {}),
    ]).finally(() => setLoadingMsgs(false));
  }, [conversationId]);

  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    function handler(e: Event) {
      const env = (e as CustomEvent<WSEnvelope>).detail;
      if (env.type === 'new_message') {
        const payload = env.payload as { conversation_id: string; message: Message };
        const msg = payload.message;
        if (!msg || !msg.id) return;
        if (msg.conversation_id === conversationId) {
          setMessages((prev) => {
            if (prev.some((m) => m.id === msg.id)) return prev;
            return [...prev, msg];
          });
        }
        setConversations((prev) => {
          const idx = prev.findIndex((c) => c.id === msg.conversation_id);
          if (idx === -1) return prev;
          const updated: Conversation = {
            ...prev[idx],
            last_message_text: msg.content,
            last_message_at: msg.created_at,
            unread_count:
              msg.conversation_id === conversationId
                ? 0
                : prev[idx].unread_count + (msg.direction === 'in' ? 1 : 0),
          };
          return [updated, ...prev.slice(0, idx), ...prev.slice(idx + 1)];
        });
      }
      if (env.type === 'conversation_updated') {
        const data = env.payload as { conversation: Conversation };
        setConversations((prev) =>
          prev.map((c) => (c.id === data.conversation.id ? { ...c, ...data.conversation } : c)),
        );
      }
    }
    window.addEventListener('crm-ws', handler);
    return () => window.removeEventListener('crm-ws', handler);
  }, [conversationId]);

  async function handleSend(e: FormEvent) {
    e.preventDefault();
    if (!reply.trim() || !conversationId) return;
    setSending(true);
    try {
      const msg = await sendReply(conversationId, reply.trim());
      setMessages((prev) => {
        if (prev.some((m) => m.id === msg.id)) return prev;
        return [...prev, msg];
      });
      setConversations((prev) => {
        const idx = prev.findIndex((c) => c.id === conversationId);
        if (idx === -1) return prev;
        const updated: Conversation = {
          ...prev[idx],
          last_message_text: msg.content,
          last_message_at: msg.created_at,
          unread_count: 0,
        };
        return [updated, ...prev.slice(0, idx), ...prev.slice(idx + 1)];
      });
      setReply('');
    } catch (err) {
      console.error('sendReply failed', err);
    } finally {
      setSending(false);
    }
  }

  const filteredConvs = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return conversations;
    return conversations.filter((c) => {
      const name = (c.contact_name || '').toLowerCase();
      const phone = (c.contact_phone || '').toLowerCase();
      const text = (c.last_message_text || '').toLowerCase();
      return name.includes(q) || phone.includes(q) || text.includes(q);
    });
  }, [conversations, query]);

  const messageGroups = useMemo(() => {
    const groups: { label: string; items: Message[] }[] = [];
    let current: { label: string; items: Message[] } | null = null;
    for (const m of messages) {
      const d = new Date(m.created_at);
      const today = new Date();
      const yday = new Date();
      yday.setDate(today.getDate() - 1);
      let label: string;
      if (d.toDateString() === today.toDateString()) label = 'Сегодня';
      else if (d.toDateString() === yday.toDateString()) label = 'Вчера';
      else
        label = d.toLocaleDateString('ru-RU', {
          day: '2-digit',
          month: 'long',
          year: d.getFullYear() !== today.getFullYear() ? 'numeric' : undefined,
        });
      if (!current || current.label !== label) {
        current = { label, items: [] };
        groups.push(current);
      }
      current.items.push(m);
    }
    return groups;
  }, [messages]);

  function statusIcon(msg: Message) {
    if (msg.direction === 'in') return null;
    if (msg.read_at) return <CheckCheck size={14} className="text-sky-200" />;
    if (msg.delivered_at) return <CheckCheck size={14} className="text-emerald-100" />;
    if (msg.sent_at) return <Check size={14} className="text-emerald-100" />;
    return <Check size={14} className="text-emerald-200" />;
  }

  return (
    <>
      <div
        className={`flex w-[390px] flex-col border-r border-gray-200 bg-white ${
          conversationId ? 'hidden md:flex' : 'flex'
        }`}
      >
        <div className="border-b border-gray-100 bg-gradient-to-b from-emerald-50/70 to-white px-5 pb-3 pt-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-xl font-bold text-gray-900">Чаты</h2>
            <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-600">
              {conversations.length}
            </span>
          </div>
          <div className="relative">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Поиск по имени, телефону, тексту…"
              className="w-full rounded-lg border border-gray-200 bg-gray-50 py-2 pl-9 pr-3 text-sm transition focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto">
          {loadingConvs ? (
            <ConvSkeleton />
          ) : filteredConvs.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center px-6 py-12 text-center">
              <MessageSquare size={36} className="mb-3 text-gray-300" />
              <p className="text-sm text-gray-500">
                {query ? 'Ничего не найдено' : 'Пока нет диалогов'}
              </p>
              {!query && (
                <p className="mt-1 text-xs text-gray-400">
                  Сообщения из WhatsApp появятся здесь автоматически
                </p>
              )}
            </div>
          ) : (
            filteredConvs.map((c) => {
              const active = c.id === conversationId;
              const name = c.contact_name || c.contact_phone || 'Неизвестный';
              return (
                <Link
                  key={c.id}
                  to={`/chat/${c.id}`}
                  className={`flex items-center gap-3 border-b border-gray-50 px-4 py-3 transition ${
                    active ? 'bg-emerald-50/80 ring-1 ring-inset ring-emerald-100' : 'hover:bg-gray-50'
                  }`}
                >
                  <Avatar name={name} size={44} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className={`truncate text-sm ${
                          c.unread_count > 0
                            ? 'font-semibold text-gray-900'
                            : 'font-medium text-gray-800'
                        }`}
                      >
                        {name}
                      </span>
                      <span
                        className={`shrink-0 text-xs ${
                          c.unread_count > 0 ? 'font-semibold text-emerald-600' : 'text-gray-400'
                        }`}
                      >
                        {formatShortTime(c.last_message_at)}
                      </span>
                    </div>
                    <div className="mt-0.5 flex items-center justify-between gap-2">
                      <p
                        className={`truncate text-xs ${
                          c.unread_count > 0 ? 'text-gray-700' : 'text-gray-500'
                        }`}
                      >
                        {c.last_message_text || 'Нет сообщений'}
                      </p>
                      {c.unread_count > 0 && (
                        <span className="flex h-5 min-w-[20px] items-center justify-center rounded-full bg-emerald-500 px-1.5 text-xs font-bold text-white shadow-sm">
                          {c.unread_count}
                        </span>
                      )}
                    </div>
                  </div>
                </Link>
              );
            })
          )}
        </div>
      </div>

      {conversationId ? (
        <div
          className="flex flex-1 flex-col"
          style={{ background: 'linear-gradient(140deg, #ecfeff 0%, #f0fdf4 45%, #f8fafc 100%)' }}
        >
          <div className="flex items-center gap-3 border-b border-gray-200 bg-white/90 px-5 py-3 backdrop-blur">
            <Link to="/" className="md:hidden">
              <ArrowLeft size={20} className="text-gray-600" />
            </Link>
            <Avatar name={activeConv?.contact_name || activeConv?.contact_phone || '?'} size={40} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-gray-900">
                {activeConv?.contact_name || activeConv?.contact_phone || '...'}
              </p>
              <p className="flex items-center gap-1.5 text-xs text-gray-500">
                {activeConv?.contact_phone && activeConv.contact_name && (
                  <>
                    <Phone size={10} />
                    <span>{activeConv.contact_phone}</span>
                    <span className="text-gray-300">В·</span>
                  </>
                )}
                {activeConv?.device_name && <span>через {activeConv.device_name}</span>}
              </p>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto px-4 py-4">
            {loadingMsgs ? (
              <div className="flex h-full items-center justify-center text-gray-400">
                <Loader2 size={20} className="mr-2 animate-spin" />
                Загрузка…
              </div>
            ) : messages.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center text-center text-gray-400">
                <MessageSquare size={40} className="mb-2 opacity-30" />
                <p className="text-sm">Пока нет сообщений</p>
              </div>
            ) : (
              messageGroups.map((g, gi) => (
                <div key={gi}>
                  <div className="my-3 flex justify-center">
                    <span className="rounded-full bg-white/80 px-3 py-1 text-[11px] font-medium text-gray-500 shadow-sm ring-1 ring-gray-100">
                      {g.label}
                    </span>
                  </div>
                  {g.items.map((msg) => (
                    <div
                      key={msg.id}
                      className={`mb-1.5 flex ${
                        msg.direction === 'out' ? 'justify-end' : 'justify-start'
                      }`}
                    >
                      <div
                        className={`max-w-[70%] rounded-2xl px-3.5 py-2 text-sm shadow-sm ${
                          msg.direction === 'out'
                            ? 'rounded-br-md bg-emerald-500 text-white'
                            : 'rounded-bl-md bg-white text-gray-900 ring-1 ring-gray-100'
                        }`}
                      >
                        <p className="whitespace-pre-wrap break-words leading-relaxed">
                          {msg.content}
                        </p>
                        <div
                          className={`mt-0.5 flex items-center justify-end gap-1 ${
                            msg.direction === 'out' ? 'text-emerald-100' : 'text-gray-400'
                          }`}
                        >
                          <span className="text-[10px]">
                            {new Date(msg.created_at).toLocaleTimeString('ru-RU', {
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </span>
                          {msg.direction === 'out' && statusIcon(msg)}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              ))
            )}
            <div ref={messagesEnd} />
          </div>

          <form
            onSubmit={handleSend}
            className="flex items-center gap-2 border-t border-gray-200 bg-white px-4 py-3"
          >
            <input
              type="text"
              value={reply}
              onChange={(e) => setReply(e.target.value)}
              placeholder="Введите сообщение…"
              className="flex-1 rounded-full border border-gray-200 bg-gray-50 px-4 py-2.5 text-sm transition focus:border-emerald-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
            />
            <button
              type="submit"
              disabled={sending || !reply.trim()}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-teal-600 text-white shadow-md shadow-emerald-500/30 transition hover:from-emerald-600 hover:to-teal-700 disabled:opacity-40 disabled:shadow-none"
            >
              {sending ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
            </button>
          </form>
        </div>
      ) : (
        <div
          className="hidden flex-1 flex-col items-center justify-center md:flex"
          style={{ background: 'linear-gradient(135deg, #f0fdf4 0%, #f0f9ff 100%)' }}
        >
          <div className="text-center">
            <div className="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-3xl bg-white shadow-md ring-1 ring-gray-100">
              <MessageSquare size={36} className="text-emerald-500" />
            </div>
            <p className="text-lg font-semibold text-gray-700">Выберите диалог</p>
            <p className="mt-1 text-sm text-gray-500">
              Выберите чат слева, чтобы начать общение
            </p>
          </div>
        </div>
      )}
    </>
  );
}

function ConvSkeleton() {
  return (
    <div className="animate-pulse">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 border-b border-gray-50 px-4 py-3">
          <div className="h-11 w-11 rounded-full bg-gray-200" />
          <div className="flex-1">
            <div className="mb-2 h-3 w-2/3 rounded bg-gray-200" />
            <div className="h-3 w-1/2 rounded bg-gray-100" />
          </div>
        </div>
      ))}
    </div>
  );
}
