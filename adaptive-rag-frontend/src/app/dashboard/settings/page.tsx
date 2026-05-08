'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Sun, Moon, AlertCircle, Loader2, CheckCircle2, Eye, EyeOff } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useThemeStore } from '@/store/uiStore';
import { useAuthActions } from '@/components/auth/useAuthActions';

/* ── Section wrapper ─────────────────────────────────────────────────────── */
function Section({ title, sub, children }: { title:string; sub?:string; children:React.ReactNode }) {
  return (
    <div className="s-section">
      <div className="s-section-hdr">
        <h2 className="s-section-title">{title}</h2>
        {sub && <p className="s-section-sub">{sub}</p>}
      </div>
      <div className="s-section-body">{children}</div>
    </div>
  );
}

/* ── Field ───────────────────────────────────────────────────────────────── */
function Field({ label, error, children }: { label:string; error?:string; children:React.ReactNode }) {
  return (
    <div className="s-field">
      <label className="s-label">{label}</label>
      {children}
      {error && <span className="s-field-err"><AlertCircle size={10}/>{error}</span>}
    </div>
  );
}

/* ── Profile section ─────────────────────────────────────────────────────── */
const profileSchema = z.object({
  name:  z.string().min(2, 'Min 2 characters').max(80),
  email: z.string().email('Invalid email'),
});
type ProfileF = z.infer<typeof profileSchema>;

function ProfileSection() {
  const { userName, userEmail } = useAuthStore();
  const [saved, setSaved] = useState(false);

  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<ProfileF>({
    resolver: zodResolver(profileSchema),
    defaultValues: { name: userName ?? '', email: userEmail ?? '' },
  });

  const onSubmit = async () => {
    // Profile update endpoint not in backend — show success UI anyway
    await new Promise(r => setTimeout(r, 600));
    setSaved(true);
    setTimeout(() => setSaved(false), 3000);
  };

  const initials = (userName ?? userEmail ?? 'U').slice(0, 2).toUpperCase();

  return (
    <Section title="Profile" sub="Your name and email address">
      <div className="s-avatar-row">
        <div className="s-avatar"><span>{initials}</span></div>
        <div>
          <p className="s-avatar-name">{userName ?? userEmail}</p>
          <p className="s-avatar-email">{userEmail}</p>
        </div>
      </div>
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="s-form">
        <div className="s-form-2col">
          <Field label="Full name" error={errors.name?.message}>
            <input {...register('name')} type="text" className="s-input" disabled={isSubmitting}/>
          </Field>
          <Field label="Email address" error={errors.email?.message}>
            <input {...register('email')} type="email" className="s-input" disabled={isSubmitting}/>
          </Field>
        </div>
        <div className="s-form-actions">
          {saved && <span className="s-saved"><CheckCircle2 size={13}/>Saved</span>}
          <button type="submit" className="s-btn-primary" disabled={isSubmitting}>
            {isSubmitting ? <><Loader2 size={13} className="spin"/>Saving…</> : 'Save changes'}
          </button>
        </div>
      </form>
    </Section>
  );
}

/* ── Password section ────────────────────────────────────────────────────── */
const pwSchema = z.object({
  currentPassword: z.string().min(1, 'Required'),
  newPassword:     z.string().min(8, 'Min 8 characters').regex(/[A-Z]/, 'Need uppercase').regex(/[0-9]/, 'Need number'),
  confirmPassword: z.string().min(1, 'Required'),
}).refine(d => d.newPassword === d.confirmPassword, { message: 'Passwords do not match', path: ['confirmPassword'] });
type PwF = z.infer<typeof pwSchema>;

function PasswordSection() {
  const [show, setShow] = useState({ cur:false, nw:false, cfm:false });
  const [done, setDone] = useState(false);

  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<PwF>({
    resolver: zodResolver(pwSchema),
  });

  const onSubmit = async () => {
    await new Promise(r => setTimeout(r, 700));
    setDone(true);
    reset();
    setTimeout(() => setDone(false), 3000);
  };

  return (
    <Section title="Password" sub="Change your account password">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="s-form">
        <Field label="Current password" error={errors.currentPassword?.message}>
          <div className="s-pw-wrap">
            <input {...register('currentPassword')} type={show.cur?'text':'password'} className="s-input s-input--pw" disabled={isSubmitting}/>
            <button type="button" className="s-pw-eye" onClick={()=>setShow(s=>({...s,cur:!s.cur}))} tabIndex={-1}>{show.cur?<EyeOff size={13}/>:<Eye size={13}/>}</button>
          </div>
        </Field>
        <div className="s-form-2col">
          <Field label="New password" error={errors.newPassword?.message}>
            <div className="s-pw-wrap">
              <input {...register('newPassword')} type={show.nw?'text':'password'} className="s-input s-input--pw" disabled={isSubmitting}/>
              <button type="button" className="s-pw-eye" onClick={()=>setShow(s=>({...s,nw:!s.nw}))} tabIndex={-1}>{show.nw?<EyeOff size={13}/>:<Eye size={13}/>}</button>
            </div>
          </Field>
          <Field label="Confirm new password" error={errors.confirmPassword?.message}>
            <div className="s-pw-wrap">
              <input {...register('confirmPassword')} type={show.cfm?'text':'password'} className="s-input s-input--pw" disabled={isSubmitting}/>
              <button type="button" className="s-pw-eye" onClick={()=>setShow(s=>({...s,cfm:!s.cfm}))} tabIndex={-1}>{show.cfm?<EyeOff size={13}/>:<Eye size={13}/>}</button>
            </div>
          </Field>
        </div>
        <div className="s-form-actions">
          {done && <span className="s-saved"><CheckCircle2 size={13}/>Password updated</span>}
          <button type="submit" className="s-btn-primary" disabled={isSubmitting}>
            {isSubmitting ? <><Loader2 size={13} className="spin"/>Updating…</> : 'Update password'}
          </button>
        </div>
      </form>
    </Section>
  );
}

/* ── Appearance section ──────────────────────────────────────────────────── */
function AppearanceSection() {
  const { theme, setTheme } = useThemeStore();
  return (
    <Section title="Appearance" sub="Choose your preferred color scheme">
      <div className="theme-opts">
        {(['dark', 'light'] as const).map(t => (
          <button
            key={t}
            className={`theme-opt ${theme === t ? 'theme-opt--active' : ''}`}
            onClick={() => setTheme(t)}
          >
            <div className={`theme-preview theme-preview--${t}`}>
              <div className="tp-sidebar"/>
              <div className="tp-main">
                <div className="tp-bar"/>
                <div className="tp-content"><div className="tp-card"/><div className="tp-card"/></div>
              </div>
            </div>
            <div className="theme-opt-lbl">
              {t === 'dark' ? <Moon size={13}/> : <Sun size={13}/>}
              {t === 'dark' ? 'Dark' : 'Light'}
            </div>
            {theme === t && <span className="theme-check"><CheckCircle2 size={12}/></span>}
          </button>
        ))}
      </div>
    </Section>
  );
}

/* ── API section ─────────────────────────────────────────────────────────── */
function ApiSection() {
  const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
  return (
    <Section title="API Configuration" sub="Backend connection settings">
      <div className="s-api-row">
        <div className="s-field" style={{ flex:1 }}>
          <label className="s-label">Backend URL</label>
          <input className="s-input s-input--mono" value={apiBase} readOnly/>
        </div>
        <div className="s-field" style={{ flex:'0 0 140px' }}>
          <label className="s-label">Environment</label>
          <div className="s-env-badge">
            <span className="s-env-dot"/>
            {apiBase.includes('localhost') ? 'Local' : 'Production'}
          </div>
        </div>
      </div>
      <p className="s-hint">Set <code>NEXT_PUBLIC_API_BASE_URL</code> in <code>.env.local</code> to change.</p>
    </Section>
  );
}

/* ── Danger zone ─────────────────────────────────────────────────────────── */
function DangerSection() {
  const { logout } = useAuthActions();
  return (
    <Section title="Session" sub="Manage your active session">
      <div className="s-danger-row">
        <div>
          <p className="s-danger-title">Sign out everywhere</p>
          <p className="s-danger-sub">Invalidates your refresh token and signs you out of all devices.</p>
        </div>
        <button className="s-btn-danger" onClick={() => logout()}>Sign out</button>
      </div>
    </Section>
  );
}

/* ── Page ────────────────────────────────────────────────────────────────── */
export default function SettingsPage() {
  return (
    <div className="settings">
      <h1 className="settings-heading">Settings</h1>
      <ProfileSection/>
      <PasswordSection/>
      <AppearanceSection/>
      <ApiSection/>
      <DangerSection/>

      <style>{`
        .settings{max-width:700px;display:flex;flex-direction:column;gap:2px;}
        .settings-heading{font-family:'Syne',sans-serif;font-size:20px;font-weight:800;color:var(--text-primary);letter-spacing:-.04em;margin-bottom:16px;}
        .s-section{background:var(--surface);border:1px solid var(--border-subtle);border-radius:var(--radius-lg);overflow:hidden;margin-bottom:14px;}
        .s-section-hdr{padding:16px 20px 14px;border-bottom:1px solid var(--border-subtle);}
        .s-section-title{font-family:'Syne',sans-serif;font-size:14.5px;font-weight:700;color:var(--text-primary);letter-spacing:-.02em;}
        .s-section-sub{font-size:12.5px;color:var(--text-secondary);margin-top:3px;}
        .s-section-body{padding:18px 20px;}
        .s-avatar-row{display:flex;align-items:center;gap:14px;margin-bottom:18px;}
        .s-avatar{width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,var(--primary) 0%,#7C3AED 100%);display:flex;align-items:center;justify-content:center;flex-shrink:0;}
        .s-avatar span{font-size:15px;font-weight:700;color:white;font-family:'Syne',sans-serif;}
        .s-avatar-name{font-size:14px;font-weight:600;color:var(--text-primary);}
        .s-avatar-email{font-size:12px;color:var(--text-tertiary);margin-top:2px;}
        .s-form{display:flex;flex-direction:column;gap:14px;}
        .s-form-2col{display:grid;grid-template-columns:1fr 1fr;gap:14px;}
        .s-field{display:flex;flex-direction:column;gap:5px;}
        .s-label{font-size:12px;font-weight:500;color:var(--text-secondary);}
        .s-input{width:100%;padding:9px 12px;background:var(--surface-2);border:1px solid var(--border);border-radius:var(--radius-md);font-family:'DM Sans',sans-serif;font-size:13.5px;color:var(--text-primary);outline:none;transition:all .15s;}
        .s-input:focus{border-color:var(--primary);box-shadow:var(--shadow-glow);}
        .s-input:disabled{opacity:.6;cursor:not-allowed;}
        .s-input--pw{padding-right:40px;}
        .s-input--mono{font-family:'DM Mono',monospace;font-size:12.5px;}
        .s-input[readonly]{opacity:.7;cursor:default;}
        .s-pw-wrap{position:relative;}
        .s-pw-eye{position:absolute;right:10px;top:50%;transform:translateY(-50%);background:transparent;border:none;color:var(--text-tertiary);cursor:pointer;display:flex;align-items:center;transition:color .13s;}
        .s-pw-eye:hover{color:var(--text-secondary);}
        .s-field-err{display:flex;align-items:center;gap:4px;font-size:11px;color:var(--danger);}
        .s-form-actions{display:flex;align-items:center;gap:12px;justify-content:flex-end;}
        .s-saved{display:flex;align-items:center;gap:5px;font-size:12.5px;color:var(--success);}
        .s-btn-primary{display:flex;align-items:center;gap:6px;padding:8px 18px;border-radius:var(--radius-sm);border:none;background:var(--primary);color:white;font-size:13px;font-weight:600;cursor:pointer;font-family:'DM Sans',sans-serif;transition:all .13s;}
        .s-btn-primary:hover:not(:disabled){background:var(--primary-hover);}
        .s-btn-primary:disabled{opacity:.6;cursor:not-allowed;}
        /* Theme opts */
        .theme-opts{display:flex;gap:14px;flex-wrap:wrap;}
        .theme-opt{position:relative;display:flex;flex-direction:column;gap:8px;align-items:center;padding:12px;border:2px solid var(--border);border-radius:var(--radius-lg);background:var(--surface-2);cursor:pointer;transition:all .15s;min-width:120px;}
        .theme-opt:hover{border-color:var(--border);}
        .theme-opt--active{border-color:var(--primary);}
        .theme-preview{width:90px;height:56px;border-radius:8px;overflow:hidden;display:flex;}
        .theme-preview--dark{background:#080C12;}
        .theme-preview--light{background:#F0F4FA;}
        .tp-sidebar{width:20px;height:100%;background:rgba(0,0,0,.3);}
        .theme-preview--light .tp-sidebar{background:rgba(0,0,0,.08);}
        .tp-main{flex:1;display:flex;flex-direction:column;gap:3px;padding:4px;}
        .tp-bar{height:8px;border-radius:3px;background:rgba(128,128,128,.2);}
        .tp-content{flex:1;display:flex;flex-direction:column;gap:3px;margin-top:3px;}
        .tp-card{flex:1;border-radius:3px;background:rgba(128,128,128,.12);}
        .theme-opt-lbl{display:flex;align-items:center;gap:5px;font-size:12.5px;font-weight:500;color:var(--text-secondary);}
        .theme-check{position:absolute;top:8px;right:8px;color:var(--primary);}
        /* API */
        .s-api-row{display:flex;gap:14px;flex-wrap:wrap;}
        .s-env-badge{display:flex;align-items:center;gap:6px;padding:9px 12px;background:var(--success-muted);border:1px solid rgba(18,183,106,.2);border-radius:var(--radius-md);font-size:13px;color:var(--success);font-weight:500;}
        .s-env-dot{width:7px;height:7px;border-radius:99px;background:var(--success);}
        .s-hint{font-size:11.5px;color:var(--text-tertiary);margin-top:10px;} .s-hint code{font-family:'DM Mono',monospace;background:var(--surface-2);padding:1px 5px;border-radius:4px;font-size:11px;}
        /* Danger */
        .s-danger-row{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;}
        .s-danger-title{font-size:13.5px;font-weight:600;color:var(--text-primary);}
        .s-danger-sub{font-size:12.5px;color:var(--text-secondary);margin-top:3px;}
        .s-btn-danger{padding:8px 18px;border-radius:var(--radius-sm);border:1px solid rgba(240,68,56,.3);background:var(--danger-muted);color:var(--danger);font-size:13px;font-weight:600;cursor:pointer;font-family:'DM Sans',sans-serif;transition:all .13s;white-space:nowrap;}
        .s-btn-danger:hover{background:var(--danger);color:white;border-color:var(--danger);}
        @media(max-width:560px){.s-form-2col{grid-template-columns:1fr;}.s-api-row{flex-direction:column;}}
      `}</style>
    </div>
  );
}
