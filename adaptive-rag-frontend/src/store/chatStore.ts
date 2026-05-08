import { create } from 'zustand';
import { ChatMessage, MessageMetadata } from '@/types';

let msgCounter = 0;
function genId() { return `msg-${++msgCounter}-${Date.now()}`; }
function genSession() { return `session-${Date.now()}`; }

interface ChatState {
  sessionId: string;
  messages: ChatMessage[];
  isStreaming: boolean;

  initSession: () => void;
  newChat: () => void;
  addMessage: (msg: Pick<ChatMessage, 'role' | 'content' | 'isStreaming'>) => string;
  appendToken: (id: string, token: string) => void;
  finalizeMessage: (id: string, metadata?: MessageMetadata) => void;
  setStreaming: (v: boolean) => void;
}

export const useChatStore = create<ChatState>()((set, get) => ({
  sessionId: genSession(),
  messages: [],
  isStreaming: false,

  initSession: () => {
    if (!get().sessionId) set({ sessionId: genSession() });
  },

  newChat: () => set({ sessionId: genSession(), messages: [], isStreaming: false }),

  addMessage: (msg) => {
    const id = genId();
    set((s) => ({
      messages: [...s.messages, { ...msg, id, timestamp: Date.now() }],
    }));
    return id;
  },

  appendToken: (id, token) =>
    set((s) => ({
      messages: s.messages.map((m) =>
        m.id === id ? { ...m, content: m.content + token } : m
      ),
    })),

  finalizeMessage: (id, metadata) =>
    set((s) => ({
      isStreaming: false,
      messages: s.messages.map((m) =>
        m.id === id ? { ...m, isStreaming: false, metadata: metadata ?? m.metadata } : m
      ),
    })),

  setStreaming: (v) => set({ isStreaming: v }),
}));