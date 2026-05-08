'use client';

import { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';

export function AuthGuard({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  useEffect(() => {
    if (!isAuthenticated()) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    } else {
      setReady(true);
    }
  }, [isAuthenticated, pathname, router]);

  if (!ready) return null;
  return <>{children}</>;
}
