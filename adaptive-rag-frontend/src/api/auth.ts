import apiClient from './client';
import { LoginRequest, SignupRequest, TokenResponse } from '@/types';

export const authApi = {
  login: (data: LoginRequest) =>
    apiClient.post<TokenResponse>('/auth/login', data).then((r) => r.data),

signup: (data: SignupRequest) =>
  apiClient.post<TokenResponse>('/auth/signup', data)
    .then((r) => r.data),

  refresh: (refreshToken: string) =>
    apiClient.post<TokenResponse>('/auth/refresh', { refreshToken }).then((r) => r.data),

  /** Backend endpoint is POST /auth/logout — requires Bearer token, no body */
  logout: () => apiClient.post('/auth/logout').catch(() => {}),
};
