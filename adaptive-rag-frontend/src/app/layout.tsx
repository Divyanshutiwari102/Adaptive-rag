'use client';

import './globals.css';
import { useEffect } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'sonner';
import { queryClient } from '@/lib/queryClient';
import { injectAuthStore } from '@/api/client';
import { useAuthStore } from '@/store/authStore';
import { useThemeStore } from '@/store/uiStore';

function BootProviders({ children }: { children: React.ReactNode }) {
  const authStore = useAuthStore();
  const { theme } = useThemeStore();

  // Wire auth store into axios client once on mount
  useEffect(() => {
    injectAuthStore(() => ({
      accessToken: authStore.accessToken,
      refreshToken: authStore.refreshToken,
      setTokens: authStore.setTokens,
      logout: authStore.logout,
    }));
  }, [authStore]);

  // Keep rag_authed cookie in sync for Edge middleware
  useEffect(() => {
    if (authStore.isAuthenticated()) {
      document.cookie = 'rag_authed=1; path=/; max-age=604800; SameSite=Lax';
    } else {
      document.cookie = 'rag_authed=; path=/; max-age=0; SameSite=Lax';
    }
  }, [authStore]);

  return <>{children}</>;
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        {/* Inline script prevents theme flash before React hydrates */}
        <script
          dangerouslySetInnerHTML={{
            __html: `
              try {
                var t = JSON.parse(localStorage.getItem('rag-theme') || '{}');
                var theme = t.state?.theme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
                if (theme === 'dark') document.documentElement.classList.add('dark');
              } catch(e) {}
            `,
          }}
        />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </head>
      <body>
        <QueryClientProvider client={queryClient}>
          <BootProviders>
            {children}
          </BootProviders>
          <Toaster
            position="top-right"
            toastOptions={{
              style: {
                background: 'var(--surface)',
                border: '1px solid var(--border)',
                color: 'var(--text-primary)',
                fontFamily: "'DM Sans', sans-serif",
                fontSize: '13.5px',
              },
            }}
          />
        </QueryClientProvider>
      </body>
    </html>
  );
}
