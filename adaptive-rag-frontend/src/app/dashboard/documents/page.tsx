'use client';

import { useCallback, useState, useEffect } from 'react';
import { useDropzone } from 'react-dropzone';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Upload, FileText, CheckCircle2, Clock, XCircle, Loader2,
  Trash2, Search, ChevronLeft, ChevronRight, RefreshCw, AlertCircle,
} from 'lucide-react';
import { toast } from 'sonner';
import { documentApi } from '@/api/documents';
import { DocumentSummary, IngestionStatus } from '@/types';

const PAGE_SIZE = 10;

const STATUS: Record<IngestionStatus, { label: string; Icon: React.ElementType; cls: string }> = {
  DONE:       { label: 'Ready',      Icon: CheckCircle2, cls: 'st--done'  },
  PROCESSING: { label: 'Processing', Icon: Loader2,      cls: 'st--proc'  },
  PENDING:    { label: 'Pending',    Icon: Clock,        cls: 'st--pend'  },
  FAILED:     { label: 'Failed',     Icon: XCircle,      cls: 'st--fail'  },
};

function StatusBadge({ status }: { status: IngestionStatus }) {
  const { label, Icon, cls } = STATUS[status];
  return (
    <span className={`st ${cls}`}>
      <Icon size={10} className={status === 'PROCESSING' ? 'spin' : ''} />
      {label}
    </span>
  );
}

/* Upload modal — collects description before submitting */
function UploadModal({ files, onConfirm, onCancel, loading }: {
  files: File[];
  onConfirm: (desc: string) => void;
  onCancel: () => void;
  loading: boolean;
}) {
  const [desc, setDesc] = useState('');
  return (
    <div className="modal-overlay">
      <div className="modal">
        <h3 className="modal-title">Upload {files.length} file{files.length > 1 ? 's' : ''}</h3>
        <div className="modal-files">
          {files.map(f => (
            <div key={f.name} className="modal-file">
              <FileText size={13} />
              <span>{f.name}</span>
              <span className="modal-fsize">{(f.size / 1024).toFixed(0)} KB</span>
            </div>
          ))}
        </div>
        <div className="modal-field">
          <label className="modal-label">Description <span className="modal-opt">(optional)</span></label>
          <textarea
            className="modal-ta"
            value={desc}
            onChange={e => setDesc(e.target.value)}
            placeholder="Brief description of the document contents…"
            rows={3}
          />
        </div>
        <div className="modal-actions">
          <button className="modal-cancel" onClick={onCancel} disabled={loading}>Cancel</button>
          <button className="modal-confirm" onClick={() => onConfirm(desc)} disabled={loading}>
            {loading ? <><Loader2 size={13} className="spin" />Uploading…</> : <>Upload</>}
          </button>
        </div>
      </div>
    </div>
  );
}

/* Drop zone */
function DropZone({ onDrop }: { onDrop: (files: File[]) => void }) {
  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: {
      'application/pdf': ['.pdf'],
      'text/plain': ['.txt'],
      'text/markdown': ['.md'],
      'application/msword': ['.doc'],
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
    },
    maxSize: 50 * 1024 * 1024,
    multiple: true,
  });

  return (
    <div {...getRootProps()} className={`dz ${isDragActive ? 'dz--active' : ''}`}>
      <input {...getInputProps()} />
      <div className="dz-icon"><Upload size={20} /></div>
      <p className="dz-title">{isDragActive ? 'Drop files here' : 'Upload documents'}</p>
      <p className="dz-sub">PDF, TXT, MD, DOC, DOCX · max 50 MB each</p>
    </div>
  );
}

/* Polling hook — keeps refetching while any doc is PENDING or PROCESSING */
function useDocumentPolling(hasActive: boolean) {
  const qc = useQueryClient();
  useEffect(() => {
    if (!hasActive) return;
    const id = setInterval(() => {
      qc.invalidateQueries({ queryKey: ['documents'] });
    }, 3000);
    return () => clearInterval(id);
  }, [hasActive, qc]);
}

export default function DocumentsPage() {
  const [page, setPage]         = useState(0);
  const [search, setSearch]     = useState('');
  const [pending, setPending]   = useState<File[] | null>(null);
  const qc = useQueryClient();

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['documents', page],
    queryFn:  () => documentApi.list({ page, size: PAGE_SIZE }),
  });

  const docs    = data?.content ?? [];
  const hasActive = docs.some(d => d.ingestionStatus === 'PENDING' || d.ingestionStatus === 'PROCESSING');
  useDocumentPolling(hasActive);

  const filtered = search
    ? docs.filter(d => d.filename.toLowerCase().includes(search.toLowerCase()))
    : docs;

  const uploadMut = useMutation({
    mutationFn: ({ files, desc }: { files: File[]; desc: string }) =>
      Promise.all(files.map(f => documentApi.upload(f, desc))),
    onSuccess: (results) => {
      toast.success(`${results.length} file${results.length > 1 ? 's' : ''} queued for ingestion`);
      qc.invalidateQueries({ queryKey: ['documents'] });
      setPending(null);
    },
    onError: () => { toast.error('Upload failed'); setPending(null); },
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => documentApi.delete(id),
    onSuccess:  () => { toast.success('Document deleted'); qc.invalidateQueries({ queryKey: ['documents'] }); },
    onError:    () => toast.error('Delete failed'),
  });

  const handleDrop = useCallback((files: File[]) => { if (files.length) setPending(files); }, []);

  return (
    <div className="docs">
      <DropZone onDrop={handleDrop} />

      {pending && (
        <UploadModal
          files={pending}
          loading={uploadMut.isPending}
          onConfirm={(desc) => uploadMut.mutate({ files: pending, desc })}
          onCancel={() => setPending(null)}
        />
      )}

      <div className="docs-card">
        {/* Header */}
        <div className="docs-hdr">
          <div className="docs-hdr-l">
            <h2 className="docs-title">All Documents</h2>
            {!isLoading && <span className="docs-count">{(data?.totalElements ?? 0).toLocaleString()}</span>}
          </div>
          <div className="docs-hdr-r">
            <div className="search-wrap">
              <Search size={12} className="search-ico" />
              <input
                className="search-inp"
                type="text"
                placeholder="Filter by name…"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
            <button className="icon-btn" onClick={() => refetch()} title="Refresh"><RefreshCw size={13} /></button>
          </div>
        </div>

        {/* Body */}
        {isError ? (
          <div className="docs-state">
            <AlertCircle size={28} />
            <p>Failed to load documents</p>
            <button className="retry-btn" onClick={() => refetch()}><RefreshCw size={12} />Retry</button>
          </div>
        ) : isLoading ? (
          <div className="docs-skeletons">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="skeleton-shimmer" style={{ height: 52, borderRadius: 8, margin: '4px 16px', animationDelay: `${i * 0.07}s` }} />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="docs-state">
            <FileText size={28} />
            <p>{search ? 'No documents match your filter' : 'No documents yet — upload one above'}</p>
          </div>
        ) : (
          <div className="docs-scroll">
            <table className="docs-table">
              <thead>
                <tr>
                  <th className="th">Name</th>
                  <th className="th">Type</th>
                  <th className="th th--r">Chunks</th>
                  <th className="th">Status</th>
                  <th className="th th--date">Uploaded</th>
                  <th className="th" />
                </tr>
              </thead>
              <tbody>
                {filtered.map(doc => (
                  <DocRow
                    key={doc.id}
                    doc={doc}
                    onDelete={() => deleteMut.mutate(doc.id)}
                    deleting={deleteMut.isPending && deleteMut.variables === doc.id}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {(data?.totalPages ?? 0) > 1 && !isError && (
          <div className="docs-pag">
            <span className="pag-info">Page {page + 1} of {data?.totalPages}</span>
            <div className="pag-btns">
              <button className="pag-btn" onClick={() => setPage(p => p - 1)} disabled={page === 0}><ChevronLeft size={14} /></button>
              <button className="pag-btn" onClick={() => setPage(p => p + 1)} disabled={page >= (data?.totalPages ?? 1) - 1}><ChevronRight size={14} /></button>
            </div>
          </div>
        )}
      </div>

      <style>{`
        .docs{max-width:960px;display:flex;flex-direction:column;gap:18px;}
        .dz{display:flex;flex-direction:column;align-items:center;gap:6px;padding:32px 24px;border:1.5px dashed var(--border);border-radius:var(--radius-lg);background:var(--surface);cursor:pointer;transition:all .15s;text-align:center;}
        .dz:hover,.dz--active{border-color:var(--primary);background:var(--primary-muted);}
        .dz-icon{width:42px;height:42px;border-radius:11px;background:var(--primary-muted);display:flex;align-items:center;justify-content:center;color:var(--primary);margin-bottom:4px;transition:transform .15s;}
        .dz:hover .dz-icon,.dz--active .dz-icon{transform:translateY(-2px);}
        .dz-title{font-family:'Syne',sans-serif;font-size:15px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .dz-sub{font-size:12px;color:var(--text-tertiary);}
        .docs-card{background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);overflow:hidden;}
        .docs-hdr{display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid var(--border-subtle);flex-wrap:wrap;gap:10px;}
        .docs-hdr-l{display:flex;align-items:center;gap:10px;}
        .docs-title{font-family:'Syne',sans-serif;font-size:14.5px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .docs-count{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);padding:2px 7px;background:var(--surface-2);border-radius:4px;}
        .docs-hdr-r{display:flex;align-items:center;gap:8px;}
        .search-wrap{position:relative;display:flex;align-items:center;}
        .search-ico{position:absolute;left:9px;color:var(--text-tertiary);pointer-events:none;}
        .search-inp{padding:6px 10px 6px 26px;border:1px solid var(--border);border-radius:var(--radius-sm);background:var(--surface-2);color:var(--text-primary);font-family:'DM Sans',sans-serif;font-size:12.5px;outline:none;width:190px;transition:all .15s;}
        .search-inp:focus{border-color:var(--primary);box-shadow:var(--shadow-glow);}
        .icon-btn{width:32px;height:32px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface-2);color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .icon-btn:hover{color:var(--text-primary);background:var(--surface-3);}
        .docs-scroll{overflow-x:auto;}
        .docs-table{width:100%;border-collapse:collapse;}
        .th{padding:9px 14px;text-align:left;font-family:'DM Mono',monospace;font-size:9.5px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:var(--text-tertiary);border-bottom:1px solid var(--border-subtle);white-space:nowrap;}
        .th--r{text-align:right;}.th--date{white-space:nowrap;}
        .docs-skeletons{padding:6px 0;}
        .docs-state{display:flex;flex-direction:column;align-items:center;gap:10px;padding:52px 24px;color:var(--text-tertiary);}
        .docs-state p{font-size:13px;}
        .retry-btn{display:flex;align-items:center;gap:6px;padding:7px 14px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface-2);color:var(--text-secondary);font-size:12.5px;cursor:pointer;transition:all .13s;}
        .retry-btn:hover{background:var(--surface-3);color:var(--text-primary);}
        .docs-pag{display:flex;align-items:center;justify-content:space-between;padding:11px 18px;border-top:1px solid var(--border-subtle);}
        .pag-info{font-family:'DM Mono',monospace;font-size:11px;color:var(--text-tertiary);}
        .pag-btns{display:flex;gap:4px;}
        .pag-btn{width:30px;height:30px;border-radius:6px;border:1px solid var(--border);background:var(--surface-2);color:var(--text-secondary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .pag-btn:hover:not(:disabled){background:var(--surface-3);color:var(--text-primary);}
        .pag-btn:disabled{opacity:.4;cursor:not-allowed;}
        .st{display:inline-flex;align-items:center;gap:4px;font-family:'DM Mono',monospace;font-size:10.5px;padding:3px 7px;border-radius:4px;white-space:nowrap;}
        .st--done{background:var(--success-muted);color:var(--success);}
        .st--proc{background:var(--primary-muted);color:var(--primary);}
        .st--pend{background:var(--warning-muted);color:var(--warning);}
        .st--fail{background:var(--danger-muted);color:var(--danger);}
        /* Modal */
        .modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:200;display:flex;align-items:center;justify-content:center;padding:24px;animation:fadeIn .15s ease;}
        .modal{background:var(--surface);border:1px solid var(--border);border-radius:var(--radius-lg);padding:24px;width:100%;max-width:440px;box-shadow:var(--shadow-lg);display:flex;flex-direction:column;gap:16px;}
        .modal-title{font-family:'Syne',sans-serif;font-size:16px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .modal-files{display:flex;flex-direction:column;gap:6px;}
        .modal-file{display:flex;align-items:center;gap:8px;padding:8px 12px;background:var(--surface-2);border-radius:var(--radius-sm);font-size:12.5px;color:var(--text-secondary);}
        .modal-file svg{flex-shrink:0;color:var(--text-tertiary);}
        .modal-file span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
        .modal-fsize{font-family:'DM Mono',monospace;font-size:10.5px;color:var(--text-tertiary);flex-shrink:0;}
        .modal-field{display:flex;flex-direction:column;gap:6px;}
        .modal-label{font-size:12px;font-weight:500;color:var(--text-secondary);}
        .modal-opt{color:var(--text-tertiary);font-weight:400;}
        .modal-ta{width:100%;padding:9px 12px;background:var(--surface-2);border:1px solid var(--border);border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13px;color:var(--text-primary);outline:none;resize:vertical;transition:all .15s;}
        .modal-ta:focus{border-color:var(--primary);box-shadow:var(--shadow-glow);}
        .modal-ta::placeholder{color:var(--text-tertiary);}
        .modal-actions{display:flex;gap:10px;justify-content:flex-end;}
        .modal-cancel{padding:8px 16px;border-radius:var(--radius-sm);border:1px solid var(--border);background:var(--surface-2);color:var(--text-secondary);font-size:13px;cursor:pointer;transition:all .13s;}
        .modal-cancel:hover{background:var(--surface-3);}
        .modal-confirm{display:flex;align-items:center;gap:6px;padding:8px 18px;border-radius:var(--radius-sm);border:none;background:var(--primary);color:white;font-size:13px;font-weight:600;cursor:pointer;transition:all .13s;}
        .modal-confirm:hover:not(:disabled){background:var(--primary-hover);}
        .modal-confirm:disabled{opacity:.6;cursor:not-allowed;}
      `}</style>
    </div>
  );
}

function DocRow({ doc, onDelete, deleting }: { doc: DocumentSummary; onDelete: () => void; deleting: boolean }) {
  const date = new Date(doc.uploadedAt).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  return (
    <tr className="doc-row">
      <td className="td td--name">
        <div className="td-name-wrap">
          <FileText size={13} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
          <div>
            <p className="td-filename">{doc.filename}</p>
            {doc.originalDescription && <p className="td-desc">{doc.originalDescription}</p>}
          </div>
        </div>
      </td>
      <td className="td"><span className="ftype">{doc.fileType.toUpperCase()}</span></td>
      <td className="td td--r">{doc.totalChunks.toLocaleString()}</td>
      <td className="td"><StatusBadge status={doc.ingestionStatus} /></td>
      <td className="td td--date">{date}</td>
      <td className="td td--act">
        <button className="del-btn" onClick={onDelete} disabled={deleting} title="Delete">
          {deleting ? <Loader2 size={12} className="spin" /> : <Trash2 size={12} />}
        </button>
      </td>
      <style>{`
        .doc-row{border-bottom:1px solid var(--border-subtle);transition:background .12s;}
        .doc-row:last-child{border-bottom:none;}
        .doc-row:hover{background:var(--surface-2);}
        .td{padding:11px 14px;font-size:13px;color:var(--text-secondary);vertical-align:middle;}
        .td--name{width:100%;}
        .td-name-wrap{display:flex;align-items:flex-start;gap:9px;}
        .td-filename{font-size:13px;font-weight:500;color:var(--text-primary);word-break:break-word;}
        .td-desc{font-size:11.5px;color:var(--text-tertiary);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:300px;}
        .td--r{text-align:right;font-family:'DM Mono',monospace;font-size:12px;}
        .td--date{font-family:'DM Mono',monospace;font-size:11.5px;color:var(--text-tertiary);white-space:nowrap;}
        .td--act{white-space:nowrap;}
        .ftype{font-family:'DM Mono',monospace;font-size:10px;padding:2px 6px;background:var(--surface-3);color:var(--text-tertiary);border-radius:4px;}
        .del-btn{width:28px;height:28px;border-radius:6px;border:1px solid transparent;background:transparent;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;justify-content:center;transition:all .13s;}
        .del-btn:hover:not(:disabled){background:var(--danger-muted);border-color:var(--danger);color:var(--danger);}
        .del-btn:disabled{opacity:.5;cursor:not-allowed;}
      `}</style>
    </tr>
  );
}
