'use client';

import { useState, useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, CheckCircle2, Clock, XCircle, Loader2, Search, BookOpen, ChevronLeft, ChevronRight } from 'lucide-react';
import { documentApi } from '@/api/documents';
import { DocumentSummary, IngestionStatus } from '@/types';

const PAGE_SIZE = 12;

const ST: Record<IngestionStatus, { label:string; Icon:React.ElementType; color:string }> = {
  DONE:       { label:'Ready',      Icon:CheckCircle2, color:'var(--success)' },
  PROCESSING: { label:'Processing', Icon:Loader2,      color:'var(--primary)' },
  PENDING:    { label:'Pending',    Icon:Clock,        color:'var(--warning)' },
  FAILED:     { label:'Failed',     Icon:XCircle,      color:'var(--danger)'  },
};

function DocCard({ doc }: { doc: DocumentSummary }) {
  const { label, Icon, color } = ST[doc.ingestionStatus];
  const date = new Date(doc.uploadedAt).toLocaleDateString('en-GB', { day:'2-digit', month:'short', year:'numeric' });
  return (
    <div className="doc-card">
      <div className="doc-card-ico"><FileText size={18}/></div>
      <div className="doc-card-body">
        <p className="doc-card-name" title={doc.filename}>{doc.filename}</p>
        {doc.originalDescription && <p className="doc-card-desc">{doc.originalDescription}</p>}
        <div className="doc-card-meta">
          <span className="doc-card-st" style={{ color }}>
            <Icon size={10} className={doc.ingestionStatus === 'PROCESSING' ? 'spin' : ''}/>
            {label}
          </span>
          {doc.ingestionStatus === 'DONE' && (
            <span className="doc-card-chunks">{doc.totalChunks.toLocaleString()} chunks</span>
          )}
          <span className="doc-card-date">{date}</span>
        </div>
      </div>
      <span className="doc-card-type">{doc.fileType.toUpperCase()}</span>
    </div>
  );
}

function EmptyKnowledge() {
  return (
    <div className="kb-empty">
      <div className="kb-empty-ico"><BookOpen size={28}/></div>
      <h3 className="kb-empty-title">Your knowledge base is empty</h3>
      <p className="kb-empty-sub">Upload documents on the Documents page to populate your knowledge base.</p>
    </div>
  );
}

export default function KnowledgePage() {
  const [page, setPage]     = useState(0);
  const [search, setSearch] = useState('');
  const qc = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ['knowledge', page],
    queryFn:  () => documentApi.list({ page, size: PAGE_SIZE }),
    staleTime: 30_000,
  });

  const docs = data?.content ?? [];
  const hasActive = docs.some(d => d.ingestionStatus === 'PENDING' || d.ingestionStatus === 'PROCESSING');

  // Poll while anything is ingesting
  useEffect(() => {
    if (!hasActive) return;
    const id = setInterval(() => qc.invalidateQueries({ queryKey: ['knowledge'] }), 4000);
    return () => clearInterval(id);
  }, [hasActive, qc]);

  const filtered = search
    ? docs.filter(d => d.filename.toLowerCase().includes(search.toLowerCase()) ||
        d.originalDescription?.toLowerCase().includes(search.toLowerCase()))
    : docs;

  const doneCount = docs.filter(d => d.ingestionStatus === 'DONE').length;
  const procCount = docs.filter(d => d.ingestionStatus === 'PROCESSING' || d.ingestionStatus === 'PENDING').length;
  const totalChunks = docs.filter(d => d.ingestionStatus === 'DONE').reduce((s, d) => s + d.totalChunks, 0);

  return (
    <div className="kb">
      {/* Header */}
      <div className="kb-hdr">
        <div>
          <h1 className="kb-heading">Knowledge Base</h1>
          <p className="kb-sub">All indexed documents available for retrieval</p>
        </div>
        <div className="kb-stats">
          <div className="kb-stat"><span className="kb-stat-v">{(data?.totalElements ?? 0).toLocaleString()}</span><span className="kb-stat-l">Documents</span></div>
          <div className="kb-stat-sep"/>
          <div className="kb-stat"><span className="kb-stat-v">{totalChunks.toLocaleString()}</span><span className="kb-stat-l">Chunks</span></div>
          {procCount > 0 && <><div className="kb-stat-sep"/><div className="kb-stat"><span className="kb-stat-v" style={{color:'var(--warning)'}}>{procCount}</span><span className="kb-stat-l">Indexing</span></div></>}
        </div>
      </div>

      {/* Search */}
      <div className="kb-search-wrap">
        <Search size={14} className="kb-search-ico"/>
        <input
          className="kb-search"
          type="text"
          placeholder="Search by name or description…"
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      </div>

      {/* Grid */}
      {isLoading ? (
        <div className="kb-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="doc-card skeleton-shimmer" style={{ height: 90 }}/>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        search ? (
          <div className="kb-empty"><BookOpen size={24}/><p>No documents match "{search}"</p></div>
        ) : (
          <EmptyKnowledge/>
        )
      ) : (
        <div className="kb-grid">
          {filtered.map(doc => <DocCard key={doc.id} doc={doc}/>)}
        </div>
      )}

      {/* Pagination */}
      {(data?.totalPages ?? 0) > 1 && (
        <div className="kb-pag">
          <span className="pag-info">Page {page + 1} of {data?.totalPages}</span>
          <div className="pag-btns">
            <button className="pag-btn" onClick={() => setPage(p => p - 1)} disabled={page === 0}><ChevronLeft size={13}/></button>
            <button className="pag-btn" onClick={() => setPage(p => p + 1)} disabled={page >= (data?.totalPages ?? 1) - 1}><ChevronRight size={13}/></button>
          </div>
        </div>
      )}

      <style>{`
        .kb{max-width:960px;display:flex;flex-direction:column;gap:18px;}
        .kb-hdr{display:flex;align-items:flex-start;justify-content:space-between;flex-wrap:wrap;gap:14px;}
        .kb-heading{font-family:'Syne',sans-serif;font-size:20px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;}
        .kb-sub{font-size:13px;color:var(--text-secondary);margin-top:3px;}
        .kb-stats{display:flex;align-items:center;gap:12px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);padding:12px 18px;}
        .kb-stat{display:flex;flex-direction:column;align-items:center;gap:1px;}
        .kb-stat-v{font-family:'Syne',sans-serif;font-size:18px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;line-height:1;}
        .kb-stat-l{font-family:'DM Mono',monospace;font-size:10px;color:var(--text-tertiary);text-transform:uppercase;letter-spacing:.06em;}
        .kb-stat-sep{width:1px;height:32px;background:var(--border-subtle);}
        .kb-search-wrap{position:relative;display:flex;align-items:center;}
        .kb-search-ico{position:absolute;left:12px;color:var(--text-tertiary);pointer-events:none;}
        .kb-search{width:100%;padding:10px 14px 10px 36px;background:var(--surface);border:1px solid var(--border);border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;color:var(--text-primary);outline:none;transition:all .15s;}
        .kb-search:focus{border-color:var(--primary);box-shadow:var(--shadow-glow);}
        .kb-search::placeholder{color:var(--text-tertiary);}
        .kb-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px;}
        .doc-card{display:flex;align-items:flex-start;gap:12px;padding:14px 16px;background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);transition:all .15s;position:relative;}
        .doc-card:hover{border-color:var(--border);box-shadow:var(--shadow-sm);}
        .doc-card-ico{width:36px;height:36px;border-radius:9px;background:var(--primary-muted);display:flex;align-items:center;justify-content:center;color:var(--primary);flex-shrink:0;}
        .doc-card-body{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px;}
        .doc-card-name{font-size:13px;font-weight:600;color:var(--text-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
        .doc-card-desc{font-size:11.5px;color:var(--text-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
        .doc-card-meta{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:2px;}
        .doc-card-st{display:flex;align-items:center;gap:3px;font-size:11px;}
        .doc-card-chunks{font-family:'DM Mono',monospace;font-size:10.5px;color:var(--text-tertiary);}
        .doc-card-date{font-family:'DM Mono',monospace;font-size:10.5px;color:var(--text-tertiary);margin-left:auto;}
        .doc-card-type{position:absolute;top:12px;right:12px;font-family:'DM Mono',monospace;font-size:9px;padding:2px 5px;background:var(--surface-3);color:var(--text-tertiary);border-radius:3px;}
        .kb-empty{display:flex;flex-direction:column;align-items:center;gap:12px;padding:64px 24px;color:var(--text-tertiary);text-align:center;}
        .kb-empty-ico{width:56px;height:56px;border-radius:14px;background:var(--surface);border:1px solid var(--border-subtle);display:flex;align-items:center;justify-content:center;}
        .kb-empty-title{font-family:'Syne',sans-serif;font-size:16px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .kb-empty-sub{font-size:13px;max-width:340px;}
        .kb-pag{display:flex;align-items:center;justify-content:space-between;}
        .pag-info{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);}
        .pag-btns{display:flex;gap:4px;}
        .pag-btn{width:30px;height:30px;border-radius:6px;border:1px solid var(--border);background:var(--surface);color:var(--text-secondary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .pag-btn:hover:not(:disabled){background:var(--surface-2);color:var(--text-primary);}
        .pag-btn:disabled{opacity:.4;cursor:not-allowed;}
        @media(max-width:640px){.kb-grid{grid-template-columns:1fr;}}
      `}</style>
    </div>
  );
}
