'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  LayoutDashboard, MessageSquare, FileText, BookOpen,
  BarChart2, Database, Settings, ChevronLeft,
} from 'lucide-react';
import { useSidebarStore } from '@/store/uiStore';

const NAV = [
  { group: 'Main', items: [
    { href: '/dashboard',           label: 'Overview',   Icon: LayoutDashboard },
    { href: '/dashboard/chat',      label: 'Chat',       Icon: MessageSquare   },
    { href: '/dashboard/documents', label: 'Documents',  Icon: FileText        },
    { href: '/dashboard/knowledge', label: 'Knowledge',  Icon: BookOpen        },
  ]},
  { group: 'Analytics', items: [
    { href: '/dashboard/analytics', label: 'Analytics',  Icon: BarChart2       },
    { href: '/dashboard/retrieval', label: 'Retrieval',  Icon: Database        },
  ]},
  { group: 'System', items: [
    { href: '/dashboard/settings',  label: 'Settings',   Icon: Settings        },
  ]},
];

export function Sidebar() {
  const pathname = usePathname();
  const { collapsed, toggle } = useSidebarStore();

  const isActive = (href: string) =>
    href === '/dashboard' ? pathname === '/dashboard' : pathname.startsWith(href);

  return (
    <>
      {!collapsed && <div className="sb-overlay" onClick={toggle} />}
      <aside className={`sb ${collapsed ? 'sb--collapsed' : ''}`}>
        {/* Header */}
        <div className="sb-hdr">
          <div className="sb-logo">
            <div className="sb-logo-mark">
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none">
                <path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="var(--sidebar-active)" opacity="0.9"/>
                <path d="M10 6L14 8.5V13.5L10 16L6 13.5V8.5L10 6Z" fill="var(--sidebar-bg)"/>
                <circle cx="10" cy="11" r="2" fill="var(--sidebar-active)"/>
              </svg>
            </div>
            {!collapsed && <span className="sb-logo-text">AdaptiveRAG</span>}
          </div>
          <button className="sb-toggle" onClick={toggle} aria-label="Toggle sidebar">
            <ChevronLeft size={13} style={{ transform: collapsed ? 'rotate(180deg)' : 'none', transition: 'transform .2s' }} />
          </button>
        </div>

        {/* Nav */}
        <nav className="sb-nav">
          {NAV.map(({ group, items }) => (
            <div key={group} className="sb-group">
              {!collapsed && <span className="sb-group-label">{group}</span>}
              {items.map(({ href, label, Icon }) => {
                const active = isActive(href);
                return (
                  <Link key={href} href={href} className={`sb-item ${active ? 'sb-item--active' : ''}`} title={collapsed ? label : undefined}>
                    <span className="sb-icon"><Icon size={15} strokeWidth={active ? 2.2 : 1.8} /></span>
                    {!collapsed && <span className="sb-label">{label}</span>}
                    {active && !collapsed && <span className="sb-dot" />}
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>

        {/* Footer */}
        {!collapsed && (
          <div className="sb-footer">
            <span className="sb-ver">v0.1.0</span>
            <span className="sb-ver-label">AdaptiveRAG</span>
          </div>
        )}
      </aside>

      <style>{`
        .sb-overlay { display:none; position:fixed; inset:0; background:rgba(0,0,0,.5); z-index:39; }
        .sb { position:fixed; top:0; left:0; height:100vh; width:240px; background:var(--sidebar-bg); border-right:1px solid var(--sidebar-border); display:flex; flex-direction:column; z-index:40; transition:width .22s cubic-bezier(.4,0,.2,1); overflow:hidden; }
        .sb--collapsed { width:60px; }
        .sb-hdr { display:flex; align-items:center; justify-content:space-between; padding:0 12px; height:60px; border-bottom:1px solid var(--sidebar-border); flex-shrink:0; }
        .sb-logo { display:flex; align-items:center; gap:10px; flex:1; min-width:0; overflow:hidden; }
        .sb-logo-mark { flex-shrink:0; width:30px; height:30px; background:rgba(59,110,250,.12); border-radius:8px; display:flex; align-items:center; justify-content:center; }
        .sb-logo-text { font-family:'Syne',sans-serif; font-size:13.5px; font-weight:700; color:#E6EDF3; letter-spacing:-.02em; white-space:nowrap; }
        .sb-toggle { flex-shrink:0; width:22px; height:22px; border-radius:5px; border:1px solid var(--sidebar-border); background:transparent; color:var(--sidebar-text-muted); cursor:pointer; display:flex; align-items:center; justify-content:center; transition:all .15s; }
        .sb-toggle:hover { background:rgba(255,255,255,.06); color:var(--sidebar-text); }
        .sb-nav { flex:1; overflow-y:auto; overflow-x:hidden; padding:10px 0; display:flex; flex-direction:column; gap:2px; scrollbar-width:none; }
        .sb-nav::-webkit-scrollbar { display:none; }
        .sb-group { padding:2px 0; }
        .sb-group+.sb-group { margin-top:6px; padding-top:10px; border-top:1px solid var(--sidebar-border); }
        .sb-group-label { display:block; font-family:'DM Mono',monospace; font-size:9.5px; font-weight:500; letter-spacing:.08em; text-transform:uppercase; color:var(--sidebar-text-muted); padding:0 14px 5px; }
        .sb-item { position:relative; display:flex; align-items:center; gap:9px; padding:7.5px 14px; margin:1px 8px; border-radius:8px; color:var(--sidebar-text); font-size:13px; font-weight:430; transition:all .13s; white-space:nowrap; overflow:hidden; }
        .sb-item:hover { background:rgba(255,255,255,.05); color:#E6EDF3; }
        .sb-item--active { background:var(--sidebar-active-bg); color:var(--sidebar-active); font-weight:500; }
        .sb-icon { flex-shrink:0; display:flex; width:16px; align-items:center; justify-content:center; }
        .sb-label { flex:1; }
        .sb-dot { position:absolute; right:12px; width:4px; height:4px; border-radius:99px; background:var(--sidebar-active); }
        .sb--collapsed .sb-item { padding:8px; margin:1px 8px; justify-content:center; }
        .sb--collapsed .sb-label, .sb--collapsed .sb-dot { display:none; }
        .sb-footer { padding:12px 14px; border-top:1px solid var(--sidebar-border); display:flex; align-items:center; gap:8px; flex-shrink:0; }
        .sb-ver { font-family:'DM Mono',monospace; font-size:9.5px; padding:2px 6px; background:rgba(59,110,250,.15); color:var(--sidebar-active); border-radius:4px; }
        .sb-ver-label { font-size:11px; color:var(--sidebar-text-muted); }
        @media(max-width:768px){ .sb-overlay{display:block;} .sb{transform:translateX(0);} .sb--collapsed{transform:translateX(-100%);width:240px;} }
      `}</style>
    </>
  );
}
