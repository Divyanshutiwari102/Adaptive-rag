import apiClient from './client';
import { AskRequest, AskResponse, CostSummary, HistoryDetail, HistorySummary, PageResponse } from '@/types';

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export const ragApi = {
  ask: (data: AskRequest) =>
    apiClient.post<AskResponse>('/api/rag/ask', data).then((r) => r.data),

  askStream: async (
    data: AskRequest,
    accessToken: string,
    onToken: (token: string) => void,
    onDone: () => void,
    onError: (err: Error) => void
  ) => {
    try {
      const response = await fetch(`${BASE_URL}/api/rag/ask/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
        },
        body: JSON.stringify(data),
      });

      if (!response.ok) {
        const errBody = await response.text().catch(() => 'Unknown error');
        throw new Error(`Stream failed: HTTP ${response.status} — ${errBody}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body reader available');

      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // Split by SSE event separator (double newline)
        const events = buffer.split('\n\n');
        buffer = events.pop() ?? '';

        for (const event of events) {
          if (!event.trim()) continue;

          // ── KEY FIX: collect ALL data: lines and join with \n ──────────
          //
          // Spring splits multi-line token content across multiple data: lines.
          // e.g. a "\n" token becomes:  data:\ndata:  (two empty data lines)
          //      a "a\nb" token becomes: data:a\ndata:b
          //
          // Old approach: `line.slice(5)` then `if (!payload) continue`
          //   → empty data: lines were skipped → "\n" tokens lost
          //   → code blocks had no newlines → "```javapublic class Main {"
          //
          // Fix: collect all data: lines per event, join with \n.
          // This correctly reconstructs "\n" from two empty data: lines,
          // and preserves multi-line content in general.
          const dataLines = event
            .split('\n')
            .filter(line => line.startsWith('data:'))
            .map(line => line.slice(5));   // remove "data:" prefix, keep content as-is

          if (dataLines.length === 0) continue;

          // Rejoin — Spring split each \n in the original into a new data: line
          const payload = dataLines.join('\n');

          if (payload === '[DONE]') {
            onDone();
            return;
          }

          if (payload.startsWith('__ERROR__')) {
            onError(new Error(payload.replace('__ERROR__:', '')));
            return;
          }

          // payload can be "" (rare) or "\n" (newline token) or " word" (normal token)
          // Only skip truly empty strings — "\n" is falsy? No: "\n" is truthy in JS ✓
          if (payload) onToken(payload);
        }
      }

      onDone();
    } catch (err) {
      onError(err instanceof Error ? err : new Error(String(err)));
    }
  },

  getHistory: (params: { sessionId?: string; page?: number; size?: number }) =>
    apiClient
      .get<PageResponse<HistorySummary>>('/api/rag/history', { params })
      .then((r) => r.data),

  getHistoryById: (id: string) =>
    apiClient.get<HistoryDetail>(`/api/rag/history/${id}`).then((r) => r.data),

  getCostCurrentMonth: () =>
    apiClient.get<CostSummary>('/api/rag/cost/current-month').then((r) => r.data),
};