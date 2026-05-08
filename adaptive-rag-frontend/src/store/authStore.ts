import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  accessExpiresAt: number | null; // Unix ms
  userEmail: string | null;
  userName: string | null;

  setTokens: (access: string, refresh: string, expiresInMs: number) => void;
  setUser: (email: string, name?: string) => void;
  logout: () => void;
  isAuthenticated: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      accessExpiresAt: null,
      userEmail: null,
      userName: null,

      setTokens: (access, refresh, expiresInMs) =>
        set({
          accessToken: access,
          refreshToken: refresh,
          accessExpiresAt: Date.now() + expiresInMs,
        }),

      setUser: (email, name) =>
        set({ userEmail: email, userName: name ?? null }),

      logout: () =>
        set({
          accessToken: null,
          refreshToken: null,
          accessExpiresAt: null,
          userEmail: null,
          userName: null,
        }),

      isAuthenticated: () => {
        const { accessToken, accessExpiresAt } = get();
        if (!accessToken) return false;
        // Consider token valid if it expires more than 30s in the future
        if (accessExpiresAt && Date.now() > accessExpiresAt - 30_000) return false;
        return true;
      },
    }),
    {
      name: 'rag-auth',
      storage: createJSONStorage(() =>
        typeof window !== 'undefined'
          ? localStorage
          : { getItem: () => null, setItem: () => {}, removeItem: () => {} }
      ),
    }
  )
);
