import axios, { AxiosInstance, InternalAxiosRequestConfig } from 'axios';

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

// ─── Auth store accessor (injected at runtime to avoid circular deps) ─────────
type AuthAccessor = () => {
  accessToken: string | null;
  refreshToken: string | null;
  setTokens: (access: string, refresh: string, expiresInMs: number) => void;
  logout: () => void;
};

let getAuth: AuthAccessor | null = null;

export function injectAuthStore(fn: AuthAccessor) {
  getAuth = fn;
}

// ─── Axios instance ───────────────────────────────────────────────────────────
const apiClient: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
});

// Request interceptor — attach Bearer token
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAuth?.().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor — handle 401 with silent refresh
let isRefreshing = false;
let refreshQueue: Array<(token: string) => void> = [];

apiClient.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config;

    if (error.response?.status === 401 && !original._retry) {
      original._retry = true;
      const auth = getAuth?.();

      if (!auth?.refreshToken) {
        auth?.logout();
        return Promise.reject(error);
      }

      if (isRefreshing) {
        return new Promise((resolve) => {
          refreshQueue.push((newToken) => {
            original.headers.Authorization = `Bearer ${newToken}`;
            resolve(apiClient(original));
          });
        });
      }

      isRefreshing = true;
      try {
        const res = await axios.post(`${BASE_URL}/auth/refresh`, {
          refreshToken: auth.refreshToken,
        });
        const { accessToken, refreshToken, accessExpiresInMs } = res.data;
        auth.setTokens(accessToken, refreshToken, accessExpiresInMs);
        refreshQueue.forEach((cb) => cb(accessToken));
        refreshQueue = [];
        original.headers.Authorization = `Bearer ${accessToken}`;
        return apiClient(original);
      } catch {
        getAuth?.().logout();
        return Promise.reject(error);
      } finally {
        isRefreshing = false;
      }
    }
    return Promise.reject(error);
  }
);

export default apiClient;
