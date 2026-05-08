'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, X, Route, Clock, CheckCircle2, XCircle, RotateCcw, ChevronDown, ChevronUp } from 'lucide-react';
import { ragApi } from '@/api/rag';
import { HistorySummary, HistoryDetail, QueryRoute } from '@/types';

const PAGE_SIZE = 15;

function RouteBadge({ route }: { route: QueryRoute }) {
  return <span className={`rb ${route === 'INDEX' ? 'rb--idx' : 'rb--gen'}`}><Route size={9}/>{route === 'INDEX' ? 'Index' : 'General'}</span>;
}

function GradeBadge({ pass }: { pass: boolean | null }) {
  if (pass === null) return null;
  return pass
    ? <span className="grade grade--pass"><CheckCircle2 size={10}/>Pass</span>
    : <span className="grade grade--fail"><XCircle size={10}/>Fail</span>;
}

/* Detail drawer */
function DetailDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const [showCtx, setShowCtx] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['history-detail', id],
    queryFn: () => ragApi.getHistoryById(id),
    staleTime: 300_000,
  });

  return (
    <div className="drawer-overlay" onClick={e => e.target === e.currentTarget && onClose()}>
      <div className="drawer">
        <div className="drawer-hdr">
          <h3 className="drawer-title">Query Detail</h3>
          <button className="drawer-close" onClick={onClose}><X size={16}/></button>
        </div>

        {isLoading ? (
          <div className="drawer-loading">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="skeleton-shimmer" style={{ height: 16, borderRadius: 6, marginBottom: 10 }} />
            ))}
          </div>
        ) : data ? (
          <div className="drawer-body">
            {/* Meta badges */}
            <div className="drawer-meta">
              <RouteBadge route={data.routeTaken}/>
              <span className="drawer-stat"><Clock size={10}/>{data.latencyMs}ms</span>
              <GradeBadge pass={data.retrievalGradePass}/>
              {data.queryRewritten && <span className="drawer-rewrite-badge"><RotateCcw size={9}/>Rewritten</span>}
            </div>

            {/* Original query */}
            <div className="drawer-section">
              <p className="drawer-section-label">Original Query</p>
              <p className="drawer-section-text">{data.originalQuery}</p>
            </div>

            {/* Rewritten query — only if rewritten */}
            {data.queryRewritten && data.rewrittenQuery && (
              <div className="drawer-section">
                <p className="drawer-section-label">Rewritten Query</p>
                <p className="drawer-section-text drawer-section-text--accent">{data.rewrittenQuery}</p>
              </div>
            )}

            {/* Answer */}
            <div className="drawer-section">
              <p className="drawer-section-label">Answer</p>
              <p className="drawer-section-text">{data.finalAnswer}</p>
            </div>

            {/* Retrieved context — collapsible */}
            {data.retrievedContext && (
              <div className="drawer-section">
                <button className="ctx-toggle" onClick={() => setShowCtx(v => !v)}>
                  <p className="drawer-section-label" style={{ margin: 0 }}>Retrieved Context</p>
                  {showCtx ? <ChevronUp size={13}/> : <ChevronDown size={13}/>}
                </button>
                {showCtx && (
                  <pre className="ctx-pre">{data.retrievedContext}</pre>
                )}
              </div>
            )}

            {/* Timestamp */}
            <p className="drawer-time">
              {new Date(data.createdAt).toLocaleString('en-GB', {
                day: '2-digit', month: 'short', year: 'numeric',
                hour: '2-digit', minute: '2-digit',
              })}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

export default function RetrievalPage() {
  const [page, setPage]       = useState(0);
  const [selected, setSelected] = useState<string | null>(null);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['history-retrieval', page],
    queryFn:  () => ragApi.getHistory({ page, size: PAGE_SIZE }),
    staleTime: 30_000,
  });

  const items = data?.content ?? [];

  return (
    <div className="ret">
      <div className="ret-hdr">
        <h1 className="ret-heading">Retrieval History</h1>
        <button className="icon-btn" onClick={() => refetch()} title="Refresh">
          <RotateCcw size={13}/>
        </button>
      </div>

      {isError && (
        <div className="ret-err">Failed to load history. <button className="ret-retry" onClick={() => refetch()}>Retry</button></div>
      )}

      <div className="ret-card">
        {isLoading ? (
          <div style={{ padding: '8px 0' }}>
            {Array.from({ length: 8 }).map((_, i) => (
              <div key={i} className="skeleton-shimmer" style={{ height: 60, margin: '4px 16px', borderRadius: 8 }} />
            ))}
          </div>
        ) : items.length === 0 ? (
          <div className="ret-empty">
            <Route size={26}/>
            <p>No query history yet — start a chat</p>
          </div>
        ) : (
          <>
            <table className="ret-table">
              <thead>
                <tr>
                  <th className="th">Query</th>
                  <th className="th">Route</th>
                  <th className="th th--r">Latency</th>
                  <th className="th">Grade</th>
                  <th className="th th--date">Time</th>
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <HistoryRow
                    key={item.id}
                    item={item}
                    onSelect={() => setSelected(item.id)}
                  />
                ))}
              </tbody>
            </table>

            {(data?.totalPages ?? 0) > 1 && (
              <div className="ret-pag">
                <span className="pag-info">Page {page + 1} of {data?.totalPages}</span>
                <div className="pag-btns">
                  <button className="pag-btn" onClick={() => setPage(p => p - 1)} disabled={page === 0}><ChevronLeft size={13}/></button>
                  <button className="pag-btn" onClick={() => setPage(p => p + 1)} disabled={page >= (data?.totalPages ?? 1) - 1}><ChevronRight size={13}/></button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {selected && <DetailDrawer id={selected} onClose={() => setSelected(null)}/>}

      <style>{`
        .ret{max-width:960px;display:flex;flex-direction:column;gap:16px;}
        .ret-hdr{display:flex;align-items:center;justify-content:space-between;}
        .ret-heading{font-family:'Syne',sans-serif;font-size:20px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;}
        .icon-btn{width:32px;height:32px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface);color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .icon-btn:hover{color:var(--text-primary);background:var(--surface-2);}
        .ret-err{padding:10px 14px;border-radius:var(--radius-md);background:var(--danger-muted);border:1px solid rgba(240,68,56,.2);color:var(--danger);font-size:13px;}
        .ret-retry{background:none;border:none;color:var(--danger);text-decoration:underline;cursor:pointer;font-size:13px;padding:0;}
        .ret-card{background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);overflow:hidden;}
        .ret-table{width:100%;border-collapse:collapse;}
        .th{padding:9px 14px;text-align:left;font-family:'DM Mono',monospace;font-size:9.5px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:var(--text-tertiary);border-bottom:1px solid var(--border-subtle);}
        .th--r{text-align:right;}.th--date{white-space:nowrap;}
        .ret-empty{display:flex;flex-direction:column;align-items:center;gap:10px;padding:52px 24px;color:var(--text-tertiary);}
        .ret-empty p{font-size:13px;}
        .ret-pag{display:flex;align-items:center;justify-content:space-between;padding:11px 18px;border-top:1px solid var(--border-subtle);}
        .pag-info{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);}
        .pag-btns{display:flex;gap:4px;}
        .pag-btn{width:28px;height:28px;border-radius:6px;border:1px solid var(--border);background:var(--surface-2);color:var(--text-secondary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .pag-btn:hover:not(:disabled){background:var(--surface-3);color:var(--text-primary);}
        .pag-btn:disabled{opacity:.4;cursor:not-allowed;}
        .rb{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;}
        .rb--idx{background:var(--primary-muted);color:var(--primary);}
        .rb--gen{background:var(--accent-muted);color:var(--accent);}
        .grade{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;}
        .grade--pass{background:var(--success-muted);color:var(--success);}
        .grade--fail{background:var(--danger-muted);color:var(--danger);}
        /* Drawer */
        .drawer-overlay{position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:200;display:flex;justify-content:flex-end;animation:fadeIn .15s ease;}
        .drawer{width:min(480px,100%);height:100%;background:var(--surface);border-left:1px solid var(--border);display:flex;flex-direction:column;box-shadow:var(--shadow-lg);}
        .drawer-hdr{display:flex;align-items:center;justify-content:space-between;padding:18px 20px;border-bottom:1px solid var(--border-subtle);flex-shrink:0;}
        .drawer-title{font-family:'Syne',sans-serif;font-size:15px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .drawer-close{width:30px;height:30px;border-radius:7px;border:1px solid var(--border-subtle);background:transparent;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .drawer-close:hover{background:var(--surface-2);color:var(--text-primary);}
        .drawer-body{flex:1;overflow-y:auto;padding:20px;display:flex;flex-direction:column;gap:16px;}
        .drawer-loading{padding:20px;display:flex;flex-direction:column;gap:8px;}
        .drawer-meta{display:flex;align-items:center;gap:8px;flex-wrap:wrap;}
        .drawer-stat{display:flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);}
        .drawer-rewrite-badge{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;background:var(--warning-muted);color:var(--warning);}
        .drawer-section{display:flex;flex-direction:column;gap:6px;}
        .drawer-section-label{font-family:'DM Mono',monospace;font-size:9.5px;font-weight:500;letter-spacing:.07em;text-transform:uppercase;color:var(--text-tertiary);}
        .drawer-section-text{font-size:13.5px;color:var(--text-primary);line-height:1.65;}
        .drawer-section-text--accent{color:var(--primary);}
        .ctx-toggle{display:flex;align-items:center;justify-content:space-between;width:100%;background:transparent;border:none;cursor:pointer;color:var(--text-tertiary);padding:0;}
        .ctx-pre{font-family:'DM Mono',monospace;font-size:11.5px;color:var(--text-secondary);background:var(--surface-2);border:1px solid var(--border-subtle);border-radius:var(--radius-md);padding:12px;white-space:pre-wrap;word-break:break-word;max-height:300px;overflow-y:auto;margin-top:8px;}
        .drawer-time{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);margin-top:8px;}
      `}</style>
    </div>
  );
}

function HistoryRow({ item, onSelect }: { item: HistorySummary; onSelect: () => void }) {
  const time = new Date(item.createdAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  return (
    <tr className="ret-row" onClick={onSelect}>
      <td className="rtd rtd--q">
        <p className="rtd-q">{item.query}</p>
        <p className="rtd-a">{item.answer}</p>
      </td>
      <td className="rtd"><RouteBadge route={item.routeTaken}/></td>
      <td className="rtd rtd--r">
        <span className="rtd-lat">{item.latencyMs}ms</span>
      </td>
      <td className="rtd">
        {item.queryRewritten && <span className="small-badge"><RotateCcw size={9}/>Rewritten</span>}
      </td>
      <td className="rtd rtd--time">{time}</td>
      <style>{`
        .ret-row{border-bottom:1px solid var(--border-subtle);cursor:pointer;transition:background .12s;}
        .ret-row:last-child{border-bottom:none;}
        .ret-row:hover{background:var(--surface-2);}
        .rtd{padding:11px 14px;vertical-align:middle;}
        .rtd--q{width:100%;}
        .rtd-q{font-size:13px;font-weight:500;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:340px;}
        .rtd-a{font-size:11.5px;color:var(--text-tertiary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:340px;margin-top:2px;}
        .rtd--r{text-align:right;}
        .rtd-lat{font-family:'DM Mono',monospace;font-size:11.5px;color:var(--text-tertiary);}
        .rtd--time{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);white-space:nowrap;}
        .small-badge{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;background:var(--warning-muted);color:var(--warning);}
      `}</style>
    </tr>
  );
}
