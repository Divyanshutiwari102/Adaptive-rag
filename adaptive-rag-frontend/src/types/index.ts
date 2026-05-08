// ─── Auth ─────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  email: string;
  password: string;
}

export interface SignupRequest {
  name: string;
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  accessExpiresInMs: number;
  tokenType: string;
}

// ─── Documents ────────────────────────────────────────────────────────────────

export type IngestionStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED';

export interface DocumentSummary {
  id: string;
  filename: string;
  fileType: string;
  totalChunks: number;
  ingestionStatus: IngestionStatus;
  ingestionError: string | null;
  originalDescription: string;
  uploadedAt: string; // ISO LocalDateTime from backend
}

export interface IngestResponse {
  id: string;
  filename: string;
  fileType: string;
  ingestionStatus: string;
  uploadedAt: string;
  message: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

// ─── RAG ──────────────────────────────────────────────────────────────────────

export type QueryRoute = 'GENERAL' | 'INDEX';

/** Matches AskRequest.java — query + optional sessionId */
export interface AskRequest {
  query: string;
  sessionId?: string;
}

/** Matches StreamingRagController.StreamRequest record */
export interface StreamRequest {
  query: string;
  sessionId?: string;
}

export interface AskResponse {
  queryId: string;
  sessionId: string;
  query: string;
  answer: string;
  routeTaken: QueryRoute;
  queryRewritten: boolean;
  rewrittenQuery: string | null;
  latencyMs: number;
  totalTokens: number;
  estimatedCostUsd: number;
}

/** Matches RagController.HistorySummary record */
export interface HistorySummary {
  id: string;
  sessionId: string;
  query: string;          // mapped from originalQuery
  answer: string;         // mapped from finalAnswer
  routeTaken: QueryRoute;
  queryRewritten: boolean;
  latencyMs: number;
  createdAt: string;      // ISO LocalDateTime
}

/** Matches RagController.HistoryDetail record */
export interface HistoryDetail {
  id: string;
  sessionId: string;
  originalQuery: string;
  rewrittenQuery: string | null;
  retrievedContext: string | null;
  finalAnswer: string;
  routeTaken: QueryRoute;
  retrievalGradePass: boolean | null;
  queryRewritten: boolean;
  latencyMs: number;
  createdAt: string;
}

/** Matches CostSummary.java DTO */
export interface CostSummary {
  periodMonth: string;       // "yyyy-MM"
  totalTokens: number;
  totalCostUsd: number;
  todayCostUsd: number;
}

// ─── Chat UI ─────────────────────────────────────────────────────────────────

export interface MessageMetadata {
  routeTaken: QueryRoute;
  latencyMs: number;
  totalTokens: number;
  estimatedCostUsd: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  isStreaming?: boolean;
  metadata?: MessageMetadata;
  timestamp: number;
}
