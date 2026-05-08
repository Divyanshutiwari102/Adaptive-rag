'use client';

import { useState, useRef, useEffect } from 'react';
import { Sun, Moon, Bell, ChevronDown, User, LogOut, Settings, Menu } from 'lucide-react';
import { useThemeStore, useSidebarStore } from '@/store/uiStore';
import { useAuthStore } from '@/store/authStore';
import { useAuthActions } from '@/components/auth/useAuthActions';

function ThemeToggle() {
  const { theme, toggle } = useThemeStore();
  return (
    <button className="nb-btn" onClick={toggle} aria-label="Toggle theme">
      {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
    </button>
  );
}

function UserMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const { userEmail, userName } = useAuthStore();
  const { logout } = useAuthActions();

  useEffect(() => {
    if (!open) return;
    const fn = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', fn);
    return () => document.removeEventListener('mousedown', fn);
  }, [open]);

  const initials = (userName ?? userEmail ?? 'U').slice(0, 2).toUpperCase();
  const display  = userName ?? userEmail ?? 'User';

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button className={`nb-user ${open ? 'nb-user--open' : ''}`} onClick={() => setOpen(v => !v)}>
        <div className="nb-avatar"><span>{initials}</span></div>
        <span className="nb-uname">{display}</span>
        <ChevronDown size={13} style={{ color: 'var(--text-tertiary)', transition: 'transform .2s', transform: open ? 'rotate(180deg)' : 'none' }} />
      </button>

      {open && (
        <div className="nb-dropdown">
          <div className="nb-dd-hdr">
            <div className="nb-avatar nb-avatar--lg"><span>{initials}</span></div>
            <div>
              <p className="nb-dd-name">{display}</p>
              {userEmail && <p className="nb-dd-email">{userEmail}</p>}
            </div>
          </div>
          <div className="nb-dd-div" />
          {[
            { Icon: User,     label: 'Profile'  },
            { Icon: Settings, label: 'Settings' },
          ].map(({ Icon, label }) => (
            <button key={label} className="nb-dd-item" onClick={() => setOpen(false)}>
              <Icon size={13} />{label}
            </button>
          ))}
          <div className="nb-dd-div" />
          <button className="nb-dd-item nb-dd-item--danger" onClick={() => { setOpen(false); logout(); }}>
            <LogOut size={13} />Sign out
          </button>
        </div>
      )}

      <style>{`
        .nb-user { display:flex; align-items:center; gap:7px; padding:3px 8px 3px 3px; border-radius:10px; border:1px solid var(--border-subtle); background:transparent; cursor:pointer; color:var(--text-primary); transition:all .13s; }
        .nb-user:hover, .nb-user--open { background:var(--surface-2); border-color:var(--border); }
        .nb-avatar { width:28px; height:28px; border-radius:8px; background:linear-gradient(135deg,var(--primary) 0%,#7C3AED 100%); display:flex; align-items:center; justify-content:center; flex-shrink:0; }
        .nb-avatar span { font-size:11px; font-weight:700; color:white; font-family:'Syne',sans-serif; }
        .nb-avatar--lg { width:34px; height:34px; border-radius:9px; }
        .nb-avatar--lg span { font-size:13px; }
        .nb-uname { font-size:12.5px; font-weight:500; color:var(--text-primary); max-width:110px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .nb-dropdown { position:absolute; top:calc(100% + 8px); right:0; min-width:210px; background:var(--surface); border:1px solid var(--border); border-radius:var(--radius-lg); box-shadow:var(--shadow-lg); overflow:hidden; z-index:100; animation:fadeIn .15s ease; }
        .nb-dd-hdr { display:flex; align-items:center; gap:10px; padding:12px 14px; }
        .nb-dd-name { font-size:13px; font-weight:600; color:var(--text-primary); }
        .nb-dd-email { font-size:11px; color:var(--text-tertiary); margin-top:1px; }
        .nb-dd-div { height:1px; background:var(--border-subtle); margin:3px 0; }
        .nb-dd-item { display:flex; align-items:center; gap:9px; width:100%; padding:9px 14px; background:transparent; border:none; color:var(--text-secondary); font-size:13px; font-family:'DM Sans',sans-serif; cursor:pointer; text-align:left; transition:all .12s; }
        .nb-dd-item:hover { background:var(--surface-2); color:var(--text-primary); }
        .nb-dd-item--danger:hover { background:var(--danger-muted); color:var(--danger); }
      `}</style>
    </div>
  );
}

export function Navbar({ title }: { title?: string }) {
  const { toggle } = useSidebarStore();
  return (
    <header className="nb">
      <div className="nb-left">
        <button className="nb-btn nb-menu" onClick={toggle} aria-label="Toggle sidebar"><Menu size={17} /></button>
        {title && <h1 className="nb-title">{title}</h1>}
      </div>
      <div className="nb-right">
        <ThemeToggle />
        <button className="nb-btn nb-bell" aria-label="Notifications">
          <Bell size={15} /><span className="nb-dot" />
        </button>
        <div className="nb-sep" />
        <UserMenu />
      </div>
      <style>{`
        .nb { position:sticky; top:0; z-index:30; display:flex; align-items:center; justify-content:space-between; height:60px; padding:0 24px; background:var(--surface); border-bottom:1px solid var(--border-subtle); box-shadow:var(--shadow-sm); }
        .nb-left { display:flex; align-items:center; gap:12px; }
        .nb-right { display:flex; align-items:center; gap:5px; }
        .nb-title { font-family:'Syne',sans-serif; font-size:15.5px; font-weight:700; color:var(--text-primary); letter-spacing:-.02em; }
        .nb-btn { position:relative; display:flex; align-items:center; justify-content:center; width:34px; height:34px; border-radius:8px; background:transparent; border:1px solid transparent; color:var(--text-secondary); cursor:pointer; transition:all .13s; }
        .nb-btn:hover { background:var(--surface-2); border-color:var(--border-subtle); color:var(--text-primary); }
        .nb-bell .nb-dot { position:absolute; top:7px; right:7px; width:5px; height:5px; border-radius:99px; background:var(--primary); border:1.5px solid var(--surface); }
        .nb-sep { width:1px; height:22px; background:var(--border-subtle); margin:0 4px; }
        .nb-menu { display:none; }
        @media(max-width:768px){ .nb-menu{display:flex;} .nb{padding:0 16px;} }
      `}</style>
    </header>
  );
}
