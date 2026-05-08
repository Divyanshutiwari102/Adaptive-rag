'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Send, RotateCcw, Route, Clock, Zap, Coins, ChevronDown } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import { useChatStore } from '@/store/chatStore';
import { useAuthStore } from '@/store/authStore';
import { ragApi } from '@/api/rag';
import { MessageMetadata, QueryRoute } from '@/types';

function RouteBadge({ route }: { route: QueryRoute }) {
  return <span className={`rb ${route==='INDEX'?'rb--idx':'rb--gen'}`}><Route size={9}/>{route==='INDEX'?'Index':'General'}</span>;
}

function MetaStrip({ meta }: { meta: MessageMetadata }) {
  return (
    <div className="meta">
      <RouteBadge route={meta.routeTaken}/>
      <span className="meta-item"><Clock size={10}/>{meta.latencyMs}ms</span>
      <span className="meta-item"><Zap size={10}/>{meta.totalTokens.toLocaleString()} tok</span>
      <span className="meta-item"><Coins size={10}/>${meta.estimatedCostUsd.toFixed(5)}</span>
    </div>
  );
}

function EmptyState({ onSuggest }: { onSuggest:(q:string)=>void }) {
  const suggestions = [
    'What documents are in my knowledge base?',
    'Summarize the key topics I have uploaded',
    'How does adaptive retrieval work?',
    'What can you help me find?',
  ];
  return (
    <div className="empty">
      <div className="empty-icon">
        <svg width="34" height="34" viewBox="0 0 20 20" fill="none"><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="var(--primary)" opacity=".12"/><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" stroke="var(--primary)" strokeWidth=".8" fill="none"/><circle cx="10" cy="11" r="2.5" fill="var(--primary)" opacity=".6"/></svg>
      </div>
      <h2 className="empty-title">Ask anything</h2>
      <p className="empty-sub">Queries route automatically — indexed documents or general knowledge.</p>
      <div className="chips">{suggestions.map(s=><button key={s} className="chip" onClick={()=>onSuggest(s)}>{s}</button>)}</div>
    </div>
  );
}

export default function ChatPage() {
  const [input, setInput] = useState('');
  const [showScroll, setShowScroll] = useState(false);
  const { messages, isStreaming, sessionId, initSession, addMessage, appendToken, finalizeMessage, setStreaming, newChat } = useChatStore();
  const { accessToken } = useAuthStore();
  const bottomRef  = useRef<HTMLDivElement>(null);
  const scrollRef  = useRef<HTMLDivElement>(null);
  const taRef      = useRef<HTMLTextAreaElement>(null);

  useEffect(() => { initSession(); }, [initSession]);
  useEffect(() => { if (isStreaming) bottomRef.current?.scrollIntoView({ behavior:'smooth' }); }, [messages, isStreaming]);
  useEffect(() => {
    const ta = taRef.current; if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  }, [input]);

  const scrollToBottom = useCallback(() => bottomRef.current?.scrollIntoView({behavior:'smooth'}), []);
  const handleScroll = () => {
    const el = scrollRef.current; if (!el) return;
    setShowScroll(el.scrollHeight - el.scrollTop - el.clientHeight > 100);
  };

  const submit = useCallback(async (text: string) => {
    const q = text.trim();
    if (!q || isStreaming) return;
    setInput('');
    addMessage({ role:'user', content: q });
    const aid = addMessage({ role:'assistant', content:'', isStreaming:true });
    setStreaming(true);
    bottomRef.current?.scrollIntoView({ behavior:'instant' });

    await ragApi.askStream(
      { query: q, sessionId },
      accessToken ?? '',
      (tok) => appendToken(aid, tok),
      () => finalizeMessage(aid),
      (err) => {
        appendToken(aid, `⚠️ Error: ${err.message}`);
        finalizeMessage(aid);
      },
    );
  }, [isStreaming, sessionId, accessToken, addMessage, appendToken, finalizeMessage, setStreaming]);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key==='Enter' && !e.shiftKey) { e.preventDefault(); submit(input); }
  };

  return (
    <div className="chat">
      {/* Messages */}
      <div className="chat-msgs" ref={scrollRef} onScroll={handleScroll}>
        {messages.length===0 ? (
          <EmptyState onSuggest={q=>{setInput(q);taRef.current?.focus();}}/>
        ) : (
          <div className="chat-list">
            {messages.map(msg => {
              const isUser = msg.role==='user';
              return (
                <div key={msg.id} className={`msg-row ${isUser?'msg-row--user':''}`}>
                  {!isUser && <div className="msg-bot-av"><svg width="14" height="14" viewBox="0 0 20 20" fill="none"><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="var(--primary)" opacity=".9"/><circle cx="10" cy="11" r="2.5" fill="var(--surface)"/></svg></div>}
                  <div className={`bubble ${isUser?'bubble--user':'bubble--bot'}`}>
                    {isUser ? (
                      <p className="bubble-txt">{msg.content}</p>
                    ) : msg.isStreaming ? (
                      // Streaming ke time: plain text — ReactMarkdown incomplete
                      // markdown ko galat parse karta hai (e.g. ```java bina closing
                      // fence ke inline code ban jaata hai). Stream finish hone ke
                      // baad proper markdown render hoti hai.
                      <div className="bubble-txt" style={{whiteSpace:'pre-wrap'}}>
                        {msg.content}
                        <span className="cursor"/>
                      </div>
                    ) : (
                      // Stream done: ab pura markdown sahi render hoga
                      <div className="bubble-txt markdown-body">
                        <ReactMarkdown>{msg.content}</ReactMarkdown>
                      </div>
                    )}
                    {!isUser && msg.metadata && <MetaStrip meta={msg.metadata}/>}
                  </div>
                </div>
              );
            })}
            <div ref={bottomRef}/>
          </div>
        )}
      </div>

      {showScroll && (
        <button className="scroll-btn" onClick={scrollToBottom} aria-label="Scroll to bottom"><ChevronDown size={14}/></button>
      )}

      {/* Input */}
      <div className="chat-bar">
        <div className="chat-input-wrap">
          <textarea ref={taRef} className="chat-ta" value={input} onChange={e=>setInput(e.target.value)} onKeyDown={onKeyDown}
            placeholder="Ask a question… (Enter to send, Shift+Enter for newline)" disabled={isStreaming} rows={1}/>
          <div className="chat-actions">
            <button className="chat-reset" onClick={newChat} disabled={isStreaming||messages.length===0} title="New chat"><RotateCcw size={13}/></button>
            <button className="chat-send" onClick={()=>submit(input)} disabled={!input.trim()||isStreaming} aria-label="Send"><Send size={13}/></button>
          </div>
        </div>
      </div>

      <style>{`
        .chat{display:flex;flex-direction:column;height:calc(100vh - 60px);margin:-28px;}
        .chat-msgs{flex:1;overflow-y:auto;padding:24px 0;position:relative;}
        .chat-list{max-width:740px;margin:0 auto;padding:0 24px;display:flex;flex-direction:column;gap:18px;}
        .empty{max-width:460px;margin:80px auto 0;padding:0 24px;text-align:center;display:flex;flex-direction:column;align-items:center;gap:12px;}
        .empty-icon{width:52px;height:52px;background:var(--primary-muted);border-radius:14px;display:flex;align-items:center;justify-content:center;}
        .empty-title{font-family:'Syne',sans-serif;font-size:22px;font-weight:700;color:var(--text-primary);letter-spacing:-.03em;}
        .empty-sub{font-size:13.5px;color:var(--text-secondary);line-height:1.65;}
        .chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin-top:4px;}
        .chip{padding:7px 14px;border-radius:99px;border:1px solid var(--border);background:var(--surface);color:var(--text-secondary);font-size:12.5px;cursor:pointer;transition:all .13s;}
        .chip:hover{border-color:var(--primary);color:var(--primary);background:var(--primary-muted);}
        .msg-row{display:flex;align-items:flex-start;gap:9px;animation:fadeIn .18s ease;}
        .msg-row--user{flex-direction:row-reverse;}
        .msg-bot-av{width:28px;height:28px;border-radius:8px;background:var(--primary-muted);display:flex;align-items:center;justify-content:center;flex-shrink:0;margin-top:2px;}
        .bubble{max-width:72%;border-radius:14px;padding:11px 14px;}
        .bubble--user{background:var(--primary);color:white;border-bottom-right-radius:3px;}
        .bubble--bot{background:var(--surface);border:1px solid var(--border-subtle);color:var(--text-primary);border-bottom-left-radius:3px;}
        .bubble-txt{font-size:13.5px;line-height:1.65;word-break:break-word;}

        /* ── Markdown styles ── */
        .markdown-body p{margin:0 0 8px;}
        .markdown-body p:last-child{margin-bottom:0;}
        .markdown-body ul,.markdown-body ol{margin:6px 0 10px;padding-left:20px;}
        .markdown-body li{margin-bottom:4px;line-height:1.6;}
        .markdown-body strong{font-weight:600;color:var(--text-primary);}
        .markdown-body em{font-style:italic;}
        .markdown-body h1,.markdown-body h2,.markdown-body h3{font-family:'Syne',sans-serif;font-weight:700;color:var(--text-primary);margin:14px 0 6px;line-height:1.3;}
        .markdown-body h1{font-size:17px;}
        .markdown-body h2{font-size:15px;}
        .markdown-body h3{font-size:13.5px;}
        .markdown-body code{font-family:'DM Mono',monospace;font-size:12px;background:var(--surface-2,rgba(0,0,0,.08));padding:1px 5px;border-radius:4px;}
        .markdown-body pre{background:var(--surface-2,#1e1e2e);border-radius:8px;padding:12px 14px;overflow-x:auto;margin:8px 0;border:1px solid var(--border-subtle);}
        .markdown-body pre code{background:none;padding:0;font-size:12px;color:var(--text-primary);}
        .markdown-body blockquote{border-left:3px solid var(--primary);padding:6px 12px;margin:8px 0;color:var(--text-secondary);font-style:italic;}
        .markdown-body hr{border:none;border-top:1px solid var(--border-subtle);margin:12px 0;}
        .markdown-body a{color:var(--primary);text-decoration:underline;text-underline-offset:2px;}
        .markdown-body table{width:100%;border-collapse:collapse;font-size:12.5px;margin:8px 0;}
        .markdown-body th,.markdown-body td{padding:6px 10px;border:1px solid var(--border-subtle);text-align:left;}
        .markdown-body th{background:var(--surface-2,rgba(0,0,0,.05));font-weight:600;}

        .cursor::after{content:'▋';display:inline-block;animation:blink .7s step-end infinite;color:var(--primary);margin-left:2px;font-size:12px;}
        .meta{display:flex;align-items:center;gap:9px;margin-top:8px;padding-top:8px;border-top:1px solid var(--border-subtle);flex-wrap:wrap;}
        .meta-item{display:flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);}
        .rb{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;}
        .rb--idx{background:var(--primary-muted);color:var(--primary);}
        .rb--gen{background:var(--accent-muted);color:var(--accent);}
        .scroll-btn{position:fixed;bottom:110px;left:50%;transform:translateX(-50%);width:32px;height:32px;border-radius:99px;background:var(--surface);border:1px solid var(--border);box-shadow:var(--shadow-md);display:flex;align-items:center;justify-content:center;color:var(--text-secondary);cursor:pointer;z-index:10;transition:all .13s;}
        .scroll-btn:hover{background:var(--surface-2);}
        .chat-bar{flex-shrink:0;padding:12px 24px 20px;background:var(--bg);}
        .chat-input-wrap{max-width:740px;margin:0 auto;display:flex;align-items:flex-end;gap:8px;background:var(--surface);border:1px solid var(--border);border-radius:14px;padding:10px 10px 10px 16px;box-shadow:var(--shadow-sm);transition:border-color .15s,box-shadow .15s;}
        .chat-input-wrap:focus-within{border-color:var(--primary);box-shadow:var(--shadow-glow);}
        .chat-ta{flex:1;border:none;background:transparent;font-family:'DM Sans',sans-serif;font-size:13.5px;color:var(--text-primary);resize:none;outline:none;line-height:1.5;max-height:160px;overflow-y:auto;}
        .chat-ta::placeholder{color:var(--text-tertiary);}
        .chat-ta:disabled{opacity:.6;cursor:not-allowed;}
        .chat-actions{display:flex;align-items:center;gap:6px;flex-shrink:0;}
        .chat-reset{width:32px;height:32px;border-radius:8px;border:1px solid var(--border-subtle);background:transparent;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .chat-reset:hover:not(:disabled){background:var(--surface-2);color:var(--text-secondary);}
        .chat-reset:disabled{opacity:.4;cursor:not-allowed;}
        .chat-send{width:34px;height:34px;border-radius:9px;border:none;background:var(--primary);color:white;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .chat-send:hover:not(:disabled){background:var(--primary-hover);transform:scale(1.04);}
        .chat-send:disabled{opacity:.4;cursor:not-allowed;transform:none;}
        @media(max-width:768px){.chat{margin:-20px -16px;}.chat-list{padding:0 16px;}.chat-bar{padding:10px 16px 16px;}.bubble{max-width:88%;}}
      `}</style>
    </div>
  );
}