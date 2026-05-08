'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Eye, EyeOff, Loader2, AlertCircle, ArrowRight } from 'lucide-react';
import { useAuthActions } from '@/components/auth/useAuthActions';

const schema = z.object({
  email:    z.string().min(1, 'Required').email('Invalid email'),
  password: z.string().min(8, 'At least 8 characters'),
});
type F = z.infer<typeof schema>;

export default function LoginPage() {
  const { login } = useAuthActions();
  const [showPw, setShowPw] = useState(false);
  const [apiErr, setApiErr] = useState<string | null>(null);

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<F>({
    resolver: zodResolver(schema), mode: 'onBlur',
  });

  const onSubmit = async (data: F) => {
    setApiErr(null);
    try {
      await login(data);
    } catch (e: unknown) {
      const s = (e as { response?: { status?: number } })?.response?.status;
      setApiErr(
        s === 401 || s === 403 ? 'Invalid email or password.' :
        s === 429 ? 'Too many attempts — wait a moment.' :
        'Connection error. Please try again.'
      );
    }
  };

  return (
    <>
      <div className="ar">
        {/* Brand panel */}
        <div className="ar-brand">
          <div className="ar-brand-inner">
            <div className="ar-logo">
              <svg width="26" height="26" viewBox="0 0 20 20" fill="none">
                <path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="#4F80FF" opacity=".9"/>
                <path d="M10 6L14 8.5V13.5L10 16L6 13.5V8.5L10 6Z" fill="#060A0F"/>
                <circle cx="10" cy="11" r="2.5" fill="#4F80FF"/>
              </svg>
              <span className="ar-logo-name">AdaptiveRAG</span>
            </div>
            <div>
              <h1 className="ar-headline">Intelligence from<br/>your documents.</h1>
              <p className="ar-sub">Adaptive retrieval that routes each query to the right source — indexed knowledge or general reasoning.</p>
            </div>
            <div className="ar-feats">
              {['Streaming responses','Adaptive query routing','Cost & token tracking'].map(f=>(
                <div key={f} className="ar-feat"><span className="ar-feat-dot"/>{f}</div>
              ))}
            </div>
            <div className="ar-footer-txt">Powered by Spring AI · PGVector · Redis</div>
          </div>
        </div>

        {/* Form panel */}
        <div className="ar-form-panel">
          <div className="ar-form-inner">
            <div className="ar-mobile-logo">
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="var(--primary)" opacity=".9"/><circle cx="10" cy="11" r="2.5" fill="var(--primary)"/></svg>
              <span className="ar-mobile-logo-name">AdaptiveRAG</span>
            </div>
            <div><h2 className="ar-form-title">Welcome back</h2><p className="ar-form-sub">Sign in to continue</p></div>
            {apiErr && <div className="ar-err"><AlertCircle size={14}/><span>{apiErr}</span></div>}
            <form onSubmit={handleSubmit(onSubmit)} noValidate className="ar-fields">
              <div className="ar-field">
                <label className="ar-label">Email</label>
                <input {...register('email')} type="email" autoComplete="email" placeholder="you@company.com"
                  className={`ar-input ${errors.email?'ar-input--err':''}`} disabled={isSubmitting}/>
                {errors.email && <span className="ar-field-err"><AlertCircle size={10}/>{errors.email.message}</span>}
              </div>
              <div className="ar-field">
                <label className="ar-label">Password</label>
                <div className="ar-pw-wrap">
                  <input {...register('password')} type={showPw?'text':'password'} autoComplete="current-password" placeholder="••••••••"
                    className={`ar-input ar-input--pw ${errors.password?'ar-input--err':''}`} disabled={isSubmitting}/>
                  <button type="button" className="ar-pw-eye" onClick={()=>setShowPw(v=>!v)} tabIndex={-1}>
                    {showPw?<EyeOff size={14}/>:<Eye size={14}/>}
                  </button>
                </div>
                {errors.password && <span className="ar-field-err"><AlertCircle size={10}/>{errors.password.message}</span>}
              </div>
              <button type="submit" className="ar-submit" disabled={isSubmitting}>
                {isSubmitting ? <><Loader2 size={14} className="spin"/>Signing in…</> : <>Sign in<ArrowRight size={14}/></>}
              </button>
            </form>
            <p className="ar-switch">Don't have an account? <Link href="/signup" className="ar-link">Create one</Link></p>
          </div>
        </div>
      </div>

      <style>{`
        html,body{height:100%;}
        .ar{display:flex;min-height:100vh;background:var(--bg);}
        .ar-brand{flex:0 0 420px;background:#0D1117;display:flex;align-items:center;justify-content:center;padding:48px;position:relative;overflow:hidden;}
        .ar-brand::before{content:'';position:absolute;inset:0;background:radial-gradient(circle at 30% 25%,rgba(79,128,255,.09) 0%,transparent 55%),radial-gradient(circle at 70% 75%,rgba(0,200,160,.05) 0%,transparent 50%);pointer-events:none;}
        .ar-brand-inner{position:relative;z-index:1;display:flex;flex-direction:column;gap:32px;max-width:320px;width:100%;}
        .ar-logo{display:flex;align-items:center;gap:10px;}
        .ar-logo-name{font-family:'Syne',sans-serif;font-size:16px;font-weight:800;color:#E6EDF3;letter-spacing:-.03em;}
        .ar-headline{font-family:'Syne',sans-serif;font-size:32px;font-weight:800;color:#E6EDF3;letter-spacing:-.04em;line-height:1.1;}
        .ar-sub{font-size:13.5px;color:#8B949E;line-height:1.7;margin-top:8px;}
        .ar-feats{display:flex;flex-direction:column;gap:9px;}
        .ar-feat{display:flex;align-items:center;gap:9px;font-size:12.5px;color:#C9D1D9;}
        .ar-feat-dot{width:5px;height:5px;border-radius:99px;background:#4F80FF;flex-shrink:0;}
        .ar-footer-txt{font-family:'DM Mono',monospace;font-size:10px;color:#484F58;padding-top:20px;border-top:1px solid rgba(255,255,255,.05);}
        .ar-form-panel{flex:1;display:flex;align-items:center;justify-content:center;padding:48px 32px;background:var(--surface);}
        .ar-form-inner{width:100%;max-width:370px;display:flex;flex-direction:column;gap:22px;}
        .ar-mobile-logo{display:none;align-items:center;gap:8px;}
        .ar-mobile-logo-name{font-family:'Syne',sans-serif;font-size:14px;font-weight:700;color:var(--text-primary);}
        .ar-form-title{font-family:'Syne',sans-serif;font-size:24px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;}
        .ar-form-sub{font-size:13px;color:var(--text-secondary);margin-top:3px;}
        .ar-err{display:flex;align-items:center;gap:8px;padding:11px 13px;background:var(--danger-muted);border:1px solid rgba(240,68,56,.25);border-radius:var(--radius-md);color:var(--danger);font-size:13px;animation:fadeIn .2s ease;}
        .ar-fields{display:flex;flex-direction:column;gap:16px;}
        .ar-field{display:flex;flex-direction:column;gap:5px;}
        .ar-label{font-size:12px;font-weight:500;color:var(--text-secondary);}
        .ar-input{width:100%;padding:10px 13px;background:var(--surface-2);border:1px solid var(--border);border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;color:var(--text-primary);outline:none;transition:all .15s;}
        .ar-input::placeholder{color:var(--text-tertiary);}
        .ar-input:focus{border-color:var(--primary);background:var(--surface);box-shadow:var(--shadow-glow);}
        .ar-input:disabled{opacity:.6;cursor:not-allowed;}
        .ar-input--err{border-color:var(--danger);background:var(--danger-muted);}
        .ar-input--pw{padding-right:42px;}
        .ar-pw-wrap{position:relative;}
        .ar-pw-eye{position:absolute;right:11px;top:50%;transform:translateY(-50%);background:transparent;border:none;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;transition:color .13s;}
        .ar-pw-eye:hover{color:var(--text-secondary);}
        .ar-field-err{display:flex;align-items:center;gap:4px;font-size:11.5px;color:var(--danger);}
        .ar-submit{display:flex;align-items:center;justify-content:center;gap:7px;width:100%;padding:11px;background:var(--primary);border:none;border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;font-weight:600;color:white;cursor:pointer;transition:all .15s;margin-top:4px;}
        .ar-submit:hover:not(:disabled){background:var(--primary-hover);transform:translateY(-1px);box-shadow:0 4px 14px rgba(59,110,250,.3);}
        .ar-submit:disabled{opacity:.6;cursor:not-allowed;transform:none;}
        .ar-switch{font-size:12.5px;color:var(--text-secondary);text-align:center;}
        .ar-link{color:var(--primary);font-weight:500;}
        .ar-link:hover{text-decoration:underline;}
        @media(max-width:860px){.ar-brand{display:none;}.ar-mobile-logo{display:flex;}.ar-form-panel{background:var(--bg);}  }
        @media(max-width:480px){.ar-form-panel{padding:32px 20px;align-items:flex-start;padding-top:48px;}}
      `}</style>
    </>
  );
}
