'use client';

import { Sidebar } from './Sidebar';
import { Navbar } from './Navbar';
import { useSidebarStore } from '@/store/uiStore';

export function DashboardLayout({ children, title }: { children: React.ReactNode; title?: string }) {
  const { collapsed } = useSidebarStore();
  return (
    <div className="dl-root">
      <Sidebar />
      <div className={`dl-main ${collapsed ? 'dl-main--x' : ''}`}>
        <Navbar title={title} />
        <main className="dl-content">{children}</main>
      </div>
      <style>{`
        .dl-root { display:flex; min-height:100vh; background:var(--bg); }
        .dl-main { flex:1; display:flex; flex-direction:column; min-width:0; margin-left:240px; transition:margin-left .22s cubic-bezier(.4,0,.2,1); }
        .dl-main--x { margin-left:60px; }
        .dl-content { flex:1; padding:28px; }
        @media(max-width:768px){ .dl-main,.dl-main--x{margin-left:0;} .dl-content{padding:20px 16px;} }
      `}</style>
    </div>
  );
}
