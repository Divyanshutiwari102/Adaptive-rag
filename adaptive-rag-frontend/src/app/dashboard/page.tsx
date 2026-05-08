'use client';

import { useQuery } from '@tanstack/react-query';
import { MessageSquare, FileText, Coins, Zap, ArrowRight, Route, Clock } from 'lucide-react';
import Link from 'next/link';
import { ragApi } from '@/api/rag';
import { documentApi } from '@/api/documents';
import { HistorySummary, QueryRoute } from '@/types';

function StatCard({ label, value, sub, icon: Icon, color }: { label:string; value:string|number; sub?:string; icon:React.ElementType; color:string }) {
  return (
    <div className="sc">
      <div className="sc-icon" style={{background:`color-mix(in srgb,${color} 12%,transparent)`,color}}><Icon size={16}/></div>
      <div><p className="sc-label">{label}</p><p className="sc-val">{value}</p>{sub&&<p className="sc-sub">{sub}</p>}</div>
    </div>
  );
}

function RouteBadge({ route }: { route: QueryRoute }) {
  return <span className={`rb ${route==='INDEX'?'rb--idx':'rb--gen'}`}><Route size={9}/>{route==='INDEX'?'Index':'General'}</span>;
}

function HistoryRow({ item }: { item: HistorySummary }) {
  const time = new Date(item.createdAt).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'});
  return (
    <div className="hr-row">
      <div className="hr-main">
        <p className="hr-q">{item.query}</p>
        <p className="hr-a">{item.answer}</p>
      </div>
      <div className="hr-meta">
        <RouteBadge route={item.routeTaken}/>
        <span className="hr-stat"><Clock size={10}/>{item.latencyMs}ms</span>
        <span className="hr-time">{time}</span>
      </div>
    </div>
  );
}

function Skeleton({ h = 14, w = '100%' }: { h?: number; w?: string | number }) {
  return (
    <div
      className="skeleton-shimmer"
      style={{ height: h, width: w, borderRadius: 6 }}
    />
  );
}

export default function DashboardPage() {
  const { data: cost,    isLoading: cl } = useQuery({ queryKey:['cost'],        queryFn: ragApi.getCostCurrentMonth, staleTime:60_000 });
  const { data: docs,    isLoading: dl } = useQuery({ queryKey:['docs-count'],  queryFn: ()=>documentApi.list({page:0,size:1}), staleTime:30_000 });
  const { data: history, isLoading: hl } = useQuery({ queryKey:['history-ov'], queryFn: ()=>ragApi.getHistory({page:0,size:6}), staleTime:30_000 });

  const items = history?.content ?? [];
  const loading = cl || dl || hl;

  return (
    <div className="ov">
      {/* Stats */}
      <div className="ov-stats">
        {loading ? Array.from({length:4}).map((_,i)=><div key={i} className="sc skeleton-shimmer" style={{height:76}}/>) : <>
          <StatCard label="Documents"    value={(docs?.totalElements??0).toLocaleString()} sub="in knowledge base"   icon={FileText}     color="var(--primary)"/>
          <StatCard label="Total Queries" value={(history?.totalElements??0).toLocaleString()} sub="this month"     icon={MessageSquare} color="var(--accent)"/>
          <StatCard label="Tokens Used"  value={(cost?.totalTokens??0).toLocaleString()}    sub="this month"        icon={Zap}           color="var(--warning)"/>
          <StatCard label="Monthly Cost" value={`$${(cost?.totalCostUsd??0).toFixed(4)}`}   sub={`$${(cost?.todayCostUsd??0).toFixed(4)} today`} icon={Coins} color="var(--primary)"/>
        </>}
      </div>

      {/* Recent queries */}
      <div className="ov-card">
        <div className="ov-card-hdr">
          <h2 className="ov-card-title">Recent Queries</h2>
          <Link href="/dashboard/chat" className="ov-link">Open chat <ArrowRight size={12}/></Link>
        </div>
        {hl ? (
          <div className="ov-list">{Array.from({length:4}).map((_,i)=><div key={i} className="hr-row"><div className="hr-main"><Skeleton h={13} w="65%"/><Skeleton h={10} w="85%"/></div></div>)}</div>
        ) : items.length===0 ? (
          <div className="ov-empty">
            <MessageSquare size={26}/>
            <p>No queries yet — start a chat</p>
            <Link href="/dashboard/chat" className="ov-cta">Start chatting <ArrowRight size={12}/></Link>
          </div>
        ) : (
          <div className="ov-list">{items.map(item=><HistoryRow key={item.id} item={item}/>)}</div>
        )}
      </div>

      {/* Quick actions */}
      <div className="ov-actions">
        {[
          {href:'/dashboard/chat',      icon:MessageSquare, title:'Ask a question',    sub:'Start a new chat session',      color:'var(--primary)'},
          {href:'/dashboard/documents', icon:FileText,      title:'Upload documents',  sub:'Add to your knowledge base',    color:'var(--accent)'},
        ].map(({href,icon:Icon,title,sub,color})=>(
          <Link key={href} href={href} className="ov-action">
            <div className="ov-action-icon" style={{background:`color-mix(in srgb,${color} 12%,transparent)`,color}}><Icon size={18}/></div>
            <div><p className="ov-action-title">{title}</p><p className="ov-action-sub">{sub}</p></div>
            <ArrowRight size={14} className="ov-action-arr"/>
          </Link>
        ))}
      </div>

      <style>{`
        .ov{max-width:920px;display:flex;flex-direction:column;gap:18px;}
        .ov-stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;}
        .sc{display:flex;align-items:center;gap:14px;padding:16px 18px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);transition:box-shadow .15s;}
        .sc:hover{box-shadow:var(--shadow-sm);}
        .sc-icon{width:40px;height:40px;border-radius:10px;display:flex;align-items:center;justify-content:center;flex-shrink:0;}
        .sc-label{font-family:'DM Mono',monospace;font-size:10px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:var(--text-tertiary);margin-bottom:2px;}
        .sc-val{font-family:'Syne',sans-serif;font-size:22px;font-weight:700;color:var(--text-primary);letter-spacing:-.03em;line-height:1.1;}
        .sc-sub{font-size:11px;color:var(--text-tertiary);margin-top:2px;}
        .ov-card{background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);overflow:hidden;}
        .ov-card-hdr{display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid var(--border-subtle);}
        .ov-card-title{font-family:'Syne',sans-serif;font-size:14px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .ov-link{display:flex;align-items:center;gap:4px;font-size:12px;color:var(--primary);font-weight:500;transition:gap .13s;}
        .ov-link:hover{gap:6px;}
        .ov-list{display:flex;flex-direction:column;}
        .hr-row{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;padding:12px 18px;border-bottom:1px solid var(--border-subtle);transition:background .12s;min-width:0;}
        .hr-row:last-child{border-bottom:none;}
        .hr-row:hover{background:var(--surface-2);}
        .hr-main{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px;}
        .hr-q{font-size:13px;font-weight:500;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
        .hr-a{font-size:12px;color:var(--text-tertiary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
        .hr-meta{display:flex;align-items:center;gap:8px;flex-shrink:0;}
        .hr-stat{display:flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);}
        .hr-time{font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);}
        .rb{display:inline-flex;align-items:center;gap:3px;font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;border-radius:4px;}
        .rb--idx{background:var(--primary-muted);color:var(--primary);}
        .rb--gen{background:var(--accent-muted);color:var(--accent);}
        .ov-empty{display:flex;flex-direction:column;align-items:center;gap:8px;padding:48px 24px;color:var(--text-tertiary);}
        .ov-empty p{font-size:13px;}
        .ov-cta{display:inline-flex;align-items:center;gap:5px;padding:7px 14px;border-radius:var(--radius-sm);background:var(--primary-muted);color:var(--primary);font-size:12.5px;font-weight:500;transition:all .13s;}
        .ov-cta:hover{background:var(--primary);color:white;}
        .ov-actions{display:grid;grid-template-columns:1fr 1fr;gap:12px;}
        .ov-action{display:flex;align-items:center;gap:14px;padding:16px 18px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);transition:all .15s;}
        .ov-action:hover{border-color:var(--primary);box-shadow:var(--shadow-sm);transform:translateY(-1px);}
        .ov-action-icon{width:40px;height:40px;border-radius:10px;display:flex;align-items:center;justify-content:center;flex-shrink:0;}
        .ov-action-title{font-size:13.5px;font-weight:600;color:var(--text-primary);}
        .ov-action-sub{font-size:11.5px;color:var(--text-tertiary);margin-top:2px;}
        .ov-action-arr{color:var(--text-tertiary);margin-left:auto;flex-shrink:0;transition:transform .15s,color .15s;}
        .ov-action:hover .ov-action-arr{transform:translateX(3px);color:var(--primary);}
        @media(max-width:640px){.ov-actions{grid-template-columns:1fr;}.ov-stats{grid-template-columns:1fr 1fr;}}
      `}</style>
    </div>
  );
}
