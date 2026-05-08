'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';
import { LoginRequest, SignupRequest } from '@/types';

function setCookie() {
  document.cookie = 'rag_authed=1; path=/; max-age=604800; SameSite=Lax';
}
function clearCookie() {
  document.cookie = 'rag_authed=; path=/; max-age=0; SameSite=Lax';
}

export function useAuthActions() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { setTokens, setUser, logout: storeLogout } = useAuthStore();

  const login = async (data: LoginRequest) => {
    const tokens = await authApi.login(data);
    setTokens(tokens.accessToken, tokens.refreshToken, tokens.accessExpiresInMs);
    setUser(data.email);
    setCookie();
    const next = searchParams.get('next') ?? '/dashboard';
    router.replace(next);
  };

  const signup = async (data: SignupRequest) => {
    const tokens = await authApi.signup(data);
    setTokens(tokens.accessToken, tokens.refreshToken, tokens.accessExpiresInMs);
    setUser(data.email, data.name);
    setCookie();
    router.replace('/dashboard');
  };

  const logout = async () => {
    await authApi.logout();
    storeLogout();
    clearCookie();
    router.replace('/login');
  };

  return { login, signup, logout };
}
