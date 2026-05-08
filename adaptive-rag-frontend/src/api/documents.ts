import apiClient from './client';
import { DocumentSummary, IngestResponse, PageResponse } from '@/types';

export const documentApi = {
  list: (params: { page?: number; size?: number }) =>
    apiClient
      .get<PageResponse<DocumentSummary>>('/api/documents', { params })
      .then((r) => r.data),

  /**
   * POST /api/documents/ingest
   * Backend requires multipart/form-data with fields:
   *   - file: MultipartFile
   *   - description: String  ← REQUIRED by controller
   */
  upload: (file: File, description = '') => {
    const form = new FormData();
    form.append('file', file);
    form.append('description', description);
    return apiClient
      .post<IngestResponse>('/api/documents/ingest', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data);
  },

  getById: (id: string) =>
    apiClient.get<DocumentSummary>(`/api/documents/${id}`).then((r) => r.data),

  delete: (id: string) =>
    apiClient.delete(`/api/documents/${id}`).then((r) => r.data),
};
