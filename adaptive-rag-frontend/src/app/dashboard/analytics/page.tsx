'use client';

import { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AreaChart, Area, BarChart, Bar, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { TrendingUp, Zap, Coins, Clock, RefreshCw, AlertCircle } from 'lucide-react';
import { ragApi } from '@/api/rag';
import { HistorySummary } from '@/types';

const C = { primary:'#4F80FF', accent:'#00C8A0', warning:'#F79009', danger:'#F04438', grid:'rgba(128,128,128,0.08)', text:'#8B949E' };

function Tip({ active, payload, label }: { active?:boolean; payload?:Array<{name:string;value:number;color:string}>; label?:string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="tip">
      {label && <p className="tip-lbl">{label}</p>}
      {payload.map(p=>(
        <div key={p.name} className="tip-row">
          <span className="tip-dot" style={{background:p.color}}/>
          <span className="tip-name">{p.name}</span>
          <span className="tip-val">{p.value}</span>
        </div>
      ))}
    </div>
  );
}

function KpiCard({ label, value, sub, icon:Icon, color }: { label:string;value:string;sub?:string;icon:React.ElementType;color:string }) {
  return (
    <div className="kpi">
      <div className="kpi-ico" style={{background:`color-mix(in srgb,${color} 12%,transparent)`,color}}><Icon size={15}/></div>
      <div><p className="kpi-lbl">{label}</p><p className="kpi-val">{value}</p>{sub&&<p className="kpi-sub">{sub}</p>}</div>
    </div>
  );
}

function buildDailySeries(items: HistorySummary[]) {
  const map = new Map<string, { date:string; index:number; general:number }>();
  items.forEach(i => {
    const d = i.createdAt.slice(0, 10);
    const e = map.get(d) ?? { date:d, index:0, general:0 };
    if (i.routeTaken === 'INDEX') e.index++; else e.general++;
    map.set(d, e);
  });
  return Array.from(map.values()).sort((a,b) => a.date.localeCompare(b.date)).slice(-14)
    .map(r => ({ ...r, date: r.date.slice(5) }));
}

function buildLatency(items: HistorySummary[]) {
  const bs = [
    { range:'<500ms', min:0,    max:500,       count:0, color:C.accent  },
    { range:'0.5-1s', min:500,  max:1000,      count:0, color:C.primary },
    { range:'1-2s',   min:1000, max:2000,      count:0, color:C.primary },
    { range:'2-4s',   min:2000, max:4000,      count:0, color:C.warning },
    { range:'>4s',    min:4000, max:Infinity,  count:0, color:C.danger  },
  ];
  items.forEach(({ latencyMs }) => {
    const b = bs.find(x => latencyMs >= x.min && latencyMs < x.max);
    if (b) b.count++;
  });
  return bs;
}

function buildPie(items: HistorySummary[]) {
  const idx = items.filter(i => i.routeTaken === 'INDEX').length;
  return [
    { name:'Index',   value:idx,               color:C.primary },
    { name:'General', value:items.length - idx, color:C.accent  },
  ];
}

export default function AnalyticsPage() {
  const [period, setPeriod] = useState<7|14|30>(14);

  const { data:cost,    isLoading:cl } = useQuery({ queryKey:['cost'],     queryFn: ragApi.getCostCurrentMonth, staleTime:60_000 });
  const { data:hist,    isLoading:hl, isError, refetch } = useQuery({
    queryKey: ['hist-analytics', period],
    queryFn:  () => ragApi.getHistory({ page:0, size: period * 50 }),
    staleTime: 60_000,
  });

  const items = useMemo(() => hist?.content ?? [], [hist]);
  const daily  = useMemo(() => buildDailySeries(items), [items]);
  const latBs  = useMemo(() => buildLatency(items),     [items]);
  const pie    = useMemo(() => buildPie(items),          [items]);
  const avgLat = items.length ? Math.round(items.reduce((s,i) => s + i.latencyMs, 0) / items.length) : 0;
  const rewritePct = items.length ? Math.round((items.filter(i => i.queryRewritten).length / items.length) * 100) : 0;
  const loading = cl || hl;

  return (
    <div className="an">
      {/* Topbar */}
      <div className="an-top">
        <h1 className="an-heading">Analytics</h1>
        <div className="an-controls">
          <div className="period-tabs">
            {([7, 14, 30] as const).map(p => (
              <button key={p} className={`pt ${period===p?'pt--on':''}`} onClick={() => setPeriod(p)}>{p}d</button>
            ))}
          </div>
          <button className="icon-btn" onClick={() => refetch()} title="Refresh"><RefreshCw size={13}/></button>
        </div>
      </div>

      {isError && (
        <div className="an-err">
          <AlertCircle size={14}/>Failed to load data.
          <button className="an-retry" onClick={() => refetch()}>Retry</button>
        </div>
      )}

      {/* KPIs */}
      <div className="an-kpis">
        {loading ? Array.from({length:4}).map((_,i) => <div key={i} className="kpi skeleton-shimmer" style={{height:76}}/>) : <>
          <KpiCard label="Total Queries"  value={items.length.toLocaleString()}               sub="in period"          icon={TrendingUp} color={C.primary} />
          <KpiCard label="Total Tokens"   value={(cost?.totalTokens??0).toLocaleString()}      sub="this month"         icon={Zap}         color={C.warning} />
          <KpiCard label="Monthly Cost"   value={`$${(cost?.totalCostUsd??0).toFixed(4)}`}    sub={`$${(cost?.todayCostUsd??0).toFixed(4)} today`} icon={Coins} color={C.accent} />
          <KpiCard label="Avg Latency"    value={`${avgLat}ms`}                                sub={`${rewritePct}% rewritten`} icon={Clock} color={C.warning} />
        </>}
      </div>

      {/* Charts */}
      <div className="an-charts">
        {/* Queries over time — wide */}
        <div className="chart-card chart-card--wide">
          <div className="chart-hdr">
            <p className="chart-title">Queries per day</p>
            <div className="chart-legend">
              <span className="cl-item"><span className="cl-dot" style={{background:C.primary}}/>Index</span>
              <span className="cl-item"><span className="cl-dot" style={{background:C.accent}}/>General</span>
            </div>
          </div>
          <div className="chart-body">
            {loading ? <div className="chart-skel skeleton-shimmer"/> : (
              <ResponsiveContainer width="100%" height={210}>
                <AreaChart data={daily} margin={{top:4,right:4,left:-24,bottom:0}}>
                  <defs>
                    <linearGradient id="gIdx" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={C.primary} stopOpacity={.22}/>
                      <stop offset="100%" stopColor={C.primary} stopOpacity={.01}/>
                    </linearGradient>
                    <linearGradient id="gGen" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={C.accent} stopOpacity={.18}/>
                      <stop offset="100%" stopColor={C.accent} stopOpacity={.01}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke={C.grid} strokeDasharray="3 3" vertical={false}/>
                  <XAxis dataKey="date" tick={{fill:C.text,fontSize:10,fontFamily:'DM Mono'}} tickLine={false} axisLine={false}/>
                  <YAxis tick={{fill:C.text,fontSize:10,fontFamily:'DM Mono'}} tickLine={false} axisLine={false} allowDecimals={false}/>
                  <Tooltip content={<Tip/>}/>
                  <Area type="monotone" dataKey="index"   name="Index"   stroke={C.primary} strokeWidth={2} fill="url(#gIdx)" dot={false}/>
                  <Area type="monotone" dataKey="general" name="General" stroke={C.accent}  strokeWidth={2} fill="url(#gGen)" dot={false}/>
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>

        {/* Route distribution */}
        <div className="chart-card">
          <div className="chart-hdr"><p className="chart-title">Route distribution</p></div>
          <div className="chart-body chart-body--c">
            {loading ? <div className="chart-skel skeleton-shimmer"/> : (
              <>
                <ResponsiveContainer width="100%" height={150}>
                  <PieChart>
                    <Pie data={pie} cx="50%" cy="50%" innerRadius={44} outerRadius={66} paddingAngle={3} dataKey="value" strokeWidth={0}>
                      {pie.map(e => <Cell key={e.name} fill={e.color} opacity={.88}/>)}
                    </Pie>
                    <Tooltip content={<Tip/>}/>
                  </PieChart>
                </ResponsiveContainer>
                <div className="pie-leg">
                  {pie.map(({name,value,color}) => (
                    <div key={name} className="pie-row">
                      <span className="pie-dot" style={{background:color}}/>
                      <span className="pie-name">{name}</span>
                      <span className="pie-n">{value}</span>
                      <span className="pie-pct">{items.length ? Math.round((value/items.length)*100) : 0}%</span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>

        {/* Latency histogram */}
        <div className="chart-card">
          <div className="chart-hdr"><p className="chart-title">Latency distribution</p></div>
          <div className="chart-body">
            {loading ? <div className="chart-skel skeleton-shimmer"/> : (
              <ResponsiveContainer width="100%" height={190}>
                <BarChart data={latBs} margin={{top:4,right:4,left:-24,bottom:0}}>
                  <CartesianGrid stroke={C.grid} strokeDasharray="3 3" vertical={false}/>
                  <XAxis dataKey="range" tick={{fill:C.text,fontSize:9,fontFamily:'DM Mono'}} tickLine={false} axisLine={false}/>
                  <YAxis tick={{fill:C.text,fontSize:10,fontFamily:'DM Mono'}} tickLine={false} axisLine={false} allowDecimals={false}/>
                  <Tooltip content={<Tip/>}/>
                  <Bar dataKey="count" name="Queries" radius={[4,4,0,0]}>
                    {latBs.map(b => <Cell key={b.range} fill={b.color} opacity={.85}/>)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </div>

      <style>{`
        .an{max-width:1000px;display:flex;flex-direction:column;gap:18px;}
        .an-top{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;}
        .an-heading{font-family:'Syne',sans-serif;font-size:20px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;}
        .an-controls{display:flex;align-items:center;gap:8px;}
        .period-tabs{display:flex;align-items:center;gap:2px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-md);padding:3px;}
        .pt{padding:5px 12px;border-radius:6px;border:none;background:transparent;font-family:'DM Sans',sans-serif;font-size:12px;font-weight:500;color:var(--text-secondary);cursor:pointer;transition:all .13s;}
        .pt--on{background:var(--primary);color:white;}
        .pt:hover:not(.pt--on){background:var(--surface-2);}
        .icon-btn{width:32px;height:32px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface);color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .icon-btn:hover{color:var(--text-primary);background:var(--surface-2);}
        .an-err{display:flex;align-items:center;gap:8px;padding:10px 14px;border-radius:var(--radius-md);background:var(--danger-muted);border:1px solid rgba(240,68,56,.2);color:var(--danger);font-size:13px;}
        .an-retry{background:none;border:none;color:var(--danger);text-decoration:underline;cursor:pointer;font-size:13px;padding:0;}
        .an-kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;}
        .kpi{display:flex;align-items:center;gap:14px;padding:15px 17px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);}
        .kpi-ico{width:38px;height:38px;border-radius:10px;display:flex;align-items:center;justify-content:center;flex-shrink:0;}
        .kpi-lbl{font-family:'DM Mono',monospace;font-size:9.5px;font-weight:500;letter-spacing:.07em;text-transform:uppercase;color:var(--text-tertiary);margin-bottom:2px;}
        .kpi-val{font-family:'Syne',sans-serif;font-size:20px;font-weight:700;color:var(--text-primary);letter-spacing:-.03em;line-height:1.1;}
        .kpi-sub{font-size:11px;color:var(--text-tertiary);margin-top:2px;}
        .an-charts{display:grid;grid-template-columns:1fr 1fr;gap:14px;}
        .chart-card{background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);overflow:hidden;}
        .chart-card--wide{grid-column:1/-1;}
        .chart-hdr{display:flex;align-items:center;justify-content:space-between;padding:13px 16px 0;}
        .chart-title{font-family:'Syne',sans-serif;font-size:13px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .chart-legend{display:flex;gap:12px;}
        .cl-item{display:flex;align-items:center;gap:5px;font-size:11px;color:var(--text-tertiary);}
        .cl-dot{width:7px;height:7px;border-radius:99px;}
        .chart-body{padding:8px 12px 14px;}
        .chart-body--c{display:flex;flex-direction:column;align-items:center;}
        .chart-skel{height:190px;border-radius:var(--radius-md);}
        .tip{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius-md);padding:10px 13px;box-shadow:var(--shadow-md);min-width:110px;}
        .tip-lbl{font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);margin-bottom:5px;text-transform:uppercase;letter-spacing:.06em;}
        .tip-row{display:flex;align-items:center;gap:6px;margin-top:3px;}
        .tip-dot{width:6px;height:6px;border-radius:99px;flex-shrink:0;}
        .tip-name{font-size:12px;color:var(--text-secondary);flex:1;}
        .tip-val{font-family:'DM Mono',monospace;font-size:12px;color:var(--text-primary);font-weight:500;}
        .pie-leg{display:flex;flex-direction:column;gap:8px;padding:0 16px 14px;width:100%;}
        .pie-row{display:flex;align-items:center;gap:8px;}
        .pie-dot{width:8px;height:8px;border-radius:99px;flex-shrink:0;}
        .pie-name{font-size:12px;color:var(--text-secondary);flex:1;}
        .pie-n{font-family:'DM Mono',monospace;font-size:12px;color:var(--text-primary);font-weight:500;}
        .pie-pct{font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);width:32px;text-align:right;}
        @media(max-width:640px){.an-charts{grid-template-columns:1fr;}.chart-card--wide{grid-column:1;}}
      `}</style>
    </div>
  );
}
