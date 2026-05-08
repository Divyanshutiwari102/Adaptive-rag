'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Eye, EyeOff, Loader2, AlertCircle, ArrowRight, CheckCircle2 } from 'lucide-react';
import { useAuthActions } from '@/components/auth/useAuthActions';

const schema = z.object({
  name:            z.string().min(2, 'Min 2 characters').max(80),
  email:           z.string().min(1,'Required').email('Invalid email'),
  password:        z.string().min(8,'Min 8 chars').regex(/[A-Z]/,'Need uppercase').regex(/[0-9]/,'Need number'),
  confirmPassword: z.string().min(1,'Required'),
}).refine(d => d.password === d.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] });

type F = z.infer<typeof schema>;

function strength(pw: string) {
  let s = 0;
  if (pw.length >= 8)  s++;
  if (pw.length >= 12) s++;
  if (/[A-Z]/.test(pw)) s++;
  if (/[0-9]/.test(pw)) s++;
  if (/[^a-zA-Z0-9]/.test(pw)) s++;
  return s;
}
const SL = ['','Weak','Weak','Fair','Strong','Very strong'];
const SC = ['','#F04438','#F04438','#F79009','#00C8A0','#00C8A0'];

export default function SignupPage() {
  const { signup } = useAuthActions();
  const [showPw,  setShowPw]  = useState(false);
  const [showCfm, setShowCfm] = useState(false);
  const [apiErr,  setApiErr]  = useState<string | null>(null);

  const { register, handleSubmit, watch, formState: { errors, isSubmitting } } = useForm<F>({
    resolver: zodResolver(schema), mode: 'onBlur',
  });
  const pw = watch('password', '');
  const s  = strength(pw);

  const onSubmit = async (data: F) => {
    setApiErr(null);
    try {
      await signup({ name: data.name, email: data.email, password: data.password });
    } catch (e: unknown) {
      const st = (e as { response?: { status?: number } })?.response?.status;
      setApiErr(
        st === 409 ? 'Email already registered. Try signing in.' :
        'Could not create account. Please try again.'
      );
    }
  };

  return (
    <>
      <div className="ar">
        <div className="ar-brand">
          <div className="ar-brand-inner">
            <div className="ar-logo">
              <svg width="26" height="26" viewBox="0 0 20 20" fill="none"><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="#4F80FF" opacity=".9"/><path d="M10 6L14 8.5V13.5L10 16L6 13.5V8.5L10 6Z" fill="#060A0F"/><circle cx="10" cy="11" r="2.5" fill="#4F80FF"/></svg>
              <span className="ar-logo-name">AdaptiveRAG</span>
            </div>
            <div>
              <h1 className="ar-headline">Build your<br/>knowledge base.</h1>
              <p className="ar-sub">Upload documents once. Query them forever with adaptive retrieval.</p>
            </div>
            <div className="ar-steps">
              {[['01','Upload','PDFs, docs, and text files'],['02','Index','Chunks embedded into PGVector'],['03','Query','Streaming cited answers']].map(([n,t,d])=>(
                <div key={n} className="ar-step">
                  <span className="ar-step-n">{n}</span>
                  <div><p className="ar-step-t">{t}</p><p className="ar-step-d">{d}</p></div>
                </div>
              ))}
            </div>
            <div className="ar-footer-txt">End-to-end encrypted · Data stays yours</div>
          </div>
        </div>

        <div className="ar-form-panel">
          <div className="ar-form-inner">
            <div className="ar-mobile-logo">
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none"><path d="M10 2L17.3 6V14L10 18L2.7 14V6L10 2Z" fill="var(--primary)" opacity=".9"/><circle cx="10" cy="11" r="2.5" fill="var(--primary)"/></svg>
              <span className="ar-mobile-logo-name">AdaptiveRAG</span>
            </div>
            <div><h2 className="ar-form-title">Create account</h2><p className="ar-form-sub">Free to start — no card required</p></div>
            {apiErr && <div className="ar-err"><AlertCircle size={14}/><span>{apiErr}</span></div>}
            <form onSubmit={handleSubmit(onSubmit)} noValidate className="ar-fields">
              {/* Name */}
              <div className="ar-field">
                <label className="ar-label">Full name</label>
                <input {...register('name')} type="text" autoComplete="name" placeholder="Jane Smith"
                  className={`ar-input ${errors.name?'ar-input--err':''}`} disabled={isSubmitting}/>
                {errors.name && <span className="ar-field-err"><AlertCircle size={10}/>{errors.name.message}</span>}
              </div>
              {/* Email */}
              <div className="ar-field">
                <label className="ar-label">Email</label>
                <input {...register('email')} type="email" autoComplete="email" placeholder="you@company.com"
                  className={`ar-input ${errors.email?'ar-input--err':''}`} disabled={isSubmitting}/>
                {errors.email && <span className="ar-field-err"><AlertCircle size={10}/>{errors.email.message}</span>}
              </div>
              {/* Password */}
              <div className="ar-field">
                <label className="ar-label">Password</label>
                <div className="ar-pw-wrap">
                  <input {...register('password')} type={showPw?'text':'password'} autoComplete="new-password" placeholder="••••••••"
                    className={`ar-input ar-input--pw ${errors.password?'ar-input--err':''}`} disabled={isSubmitting}/>
                  <button type="button" className="ar-pw-eye" onClick={()=>setShowPw(v=>!v)} tabIndex={-1}>{showPw?<EyeOff size={14}/>:<Eye size={14}/>}</button>
                </div>
                {pw && (
                  <>
                    <div className="ar-str">
                      <div className="ar-str-bars">{Array.from({length:5},(_,i)=><div key={i} className="ar-str-bar" style={{background:i<s?SC[s]:'var(--border)'}}/>)}</div>
                      <span className="ar-str-lbl" style={{color:SC[s]}}>{SL[s]}</span>
                    </div>
                    <div className="ar-rules">
                      {[['At least 8 chars', pw.length>=8],['Uppercase letter',/[A-Z]/.test(pw)],['One number',/[0-9]/.test(pw)]].map(([l,ok])=>(
                        <div key={l as string} className={`ar-rule ${ok?'ar-rule--ok':''}`}><CheckCircle2 size={10}/>{l}</div>
                      ))}
                    </div>
                  </>
                )}
                {errors.password && <span className="ar-field-err"><AlertCircle size={10}/>{errors.password.message}</span>}
              </div>
              {/* Confirm */}
              <div className="ar-field">
                <label className="ar-label">Confirm password</label>
                <div className="ar-pw-wrap">
                  <input {...register('confirmPassword')} type={showCfm?'text':'password'} autoComplete="new-password" placeholder="••••••••"
                    className={`ar-input ar-input--pw ${errors.confirmPassword?'ar-input--err':''}`} disabled={isSubmitting}/>
                  <button type="button" className="ar-pw-eye" onClick={()=>setShowCfm(v=>!v)} tabIndex={-1}>{showCfm?<EyeOff size={14}/>:<Eye size={14}/>}</button>
                </div>
                {errors.confirmPassword && <span className="ar-field-err"><AlertCircle size={10}/>{errors.confirmPassword.message}</span>}
              </div>

              <button type="submit" className="ar-submit" disabled={isSubmitting}>
                {isSubmitting ? <><Loader2 size={14} className="spin"/>Creating…</> : <>Create account<ArrowRight size={14}/></>}
              </button>
              <p className="ar-terms">By signing up you agree to our <a href="#" className="ar-link">Terms</a> & <a href="#" className="ar-link">Privacy</a>.</p>
            </form>
            <p className="ar-switch">Already have an account? <Link href="/login" className="ar-link">Sign in</Link></p>
          </div>
        </div>
      </div>

      <style>{`
        html,body{height:100%;}
        .ar{display:flex;min-height:100vh;background:var(--bg);}
        .ar-brand{flex:0 0 400px;background:#0D1117;display:flex;align-items:center;justify-content:center;padding:48px;position:relative;overflow:hidden;}
        .ar-brand::before{content:'';position:absolute;inset:0;background:radial-gradient(circle at 25% 30%,rgba(79,128,255,.09) 0%,transparent 55%),radial-gradient(circle at 75% 70%,rgba(0,200,160,.05) 0%,transparent 50%);pointer-events:none;}
        .ar-brand-inner{position:relative;z-index:1;display:flex;flex-direction:column;gap:28px;max-width:310px;width:100%;}
        .ar-logo{display:flex;align-items:center;gap:10px;}
        .ar-logo-name{font-family:'Syne',sans-serif;font-size:16px;font-weight:800;color:#E6EDF3;letter-spacing:-.03em;}
        .ar-headline{font-family:'Syne',sans-serif;font-size:28px;font-weight:800;color:#E6EDF3;letter-spacing:-.04em;line-height:1.1;}
        .ar-sub{font-size:13px;color:#8B949E;line-height:1.7;margin-top:7px;}
        .ar-steps{display:flex;flex-direction:column;gap:12px;}
        .ar-step{display:flex;align-items:flex-start;gap:10px;}
        .ar-step-n{font-family:'DM Mono',monospace;font-size:9px;font-weight:500;color:#4F80FF;background:rgba(79,128,255,.12);padding:2px 5px;border-radius:3px;flex-shrink:0;margin-top:1px;}
        .ar-step-t{font-size:12px;font-weight:600;color:#C9D1D9;}
        .ar-step-d{font-size:11px;color:#6E7681;margin-top:1px;}
        .ar-footer-txt{font-family:'DM Mono',monospace;font-size:10px;color:#484F58;padding-top:16px;border-top:1px solid rgba(255,255,255,.05);}
        .ar-form-panel{flex:1;display:flex;align-items:center;justify-content:center;padding:40px 32px;background:var(--surface);overflow-y:auto;}
        .ar-form-inner{width:100%;max-width:370px;display:flex;flex-direction:column;gap:18px;}
        .ar-mobile-logo{display:none;align-items:center;gap:8px;}
        .ar-mobile-logo-name{font-family:'Syne',sans-serif;font-size:14px;font-weight:700;color:var(--text-primary);}
        .ar-form-title{font-family:'Syne',sans-serif;font-size:22px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;}
        .ar-form-sub{font-size:13px;color:var(--text-secondary);margin-top:3px;}
        .ar-err{display:flex;align-items:center;gap:8px;padding:10px 13px;background:var(--danger-muted);border:1px solid rgba(240,68,56,.25);border-radius:var(--radius-md);color:var(--danger);font-size:12.5px;animation:fadeIn .2s ease;}
        .ar-fields{display:flex;flex-direction:column;gap:13px;}
        .ar-field{display:flex;flex-direction:column;gap:5px;}
        .ar-label{font-size:12px;font-weight:500;color:var(--text-secondary);}
        .ar-input{width:100%;padding:9px 13px;background:var(--surface-2);border:1px solid var(--border);border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;color:var(--text-primary);outline:none;transition:all .15s;}
        .ar-input::placeholder{color:var(--text-tertiary);}
        .ar-input:focus{border-color:var(--primary);background:var(--surface);box-shadow:var(--shadow-glow);}
        .ar-input:disabled{opacity:.6;cursor:not-allowed;}
        .ar-input--err{border-color:var(--danger);background:var(--danger-muted);}
        .ar-input--pw{padding-right:42px;}
        .ar-pw-wrap{position:relative;}
        .ar-pw-eye{position:absolute;right:11px;top:50%;transform:translateY(-50%);background:transparent;border:none;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;transition:color .13s;}
        .ar-pw-eye:hover{color:var(--text-secondary);}
        .ar-field-err{display:flex;align-items:center;gap:4px;font-size:11px;color:var(--danger);}
        .ar-str{display:flex;align-items:center;gap:8px;margin-top:4px;}
        .ar-str-bars{display:flex;gap:3px;flex:1;}
        .ar-str-bar{height:3px;flex:1;border-radius:99px;transition:background .2s;}
        .ar-str-lbl{font-family:'DM Mono',monospace;font-size:10px;font-weight:500;min-width:64px;text-align:right;}
        .ar-rules{display:flex;flex-direction:column;gap:3px;margin-top:3px;}
        .ar-rule{display:flex;align-items:center;gap:5px;font-size:11px;color:var(--text-tertiary);transition:color .15s;}
        .ar-rule--ok{color:var(--accent);}
        .ar-submit{display:flex;align-items:center;justify-content:center;gap:7px;width:100%;padding:11px;background:var(--primary);border:none;border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;font-weight:600;color:white;cursor:pointer;transition:all .15s;margin-top:4px;}
        .ar-submit:hover:not(:disabled){background:var(--primary-hover);transform:translateY(-1px);box-shadow:0 4px 14px rgba(59,110,250,.3);}
        .ar-submit:disabled{opacity:.6;cursor:not-allowed;transform:none;}
        .ar-terms{font-size:11px;color:var(--text-tertiary);text-align:center;}
        .ar-switch{font-size:12.5px;color:var(--text-secondary);text-align:center;}
        .ar-link{color:var(--primary);font-weight:500;}
        .ar-link:hover{text-decoration:underline;}
        @media(max-width:860px){.ar-brand{display:none;}.ar-mobile-logo{display:flex;}.ar-form-panel{background:var(--bg);}  }
        @media(max-width:480px){.ar-form-panel{padding:32px 20px;align-items:flex-start;padding-top:48px;}}
      `}</style>
    </>
  );
}
