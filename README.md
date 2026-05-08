# Adaptive RAG — Cloud-Native Distributed Document Retrieval Platform

A production-grade document Q&A system built with Java Spring Boot and Next.js. Upload PDFs, ask questions in natural language, and get accurate answers backed by your documents — with intelligent query routing, self-correcting retrieval, and real-time streaming responses.

---

## What Makes This Different From a Basic RAG

Most RAG implementations do the same thing for every query: embed it, run a vector search, stuff chunks into a prompt. This project doesn't.

Every query goes through a **routing decision** before touching the database:

- **Chitchat / greetings** → answered directly by the LLM, no retrieval at all
- **Short definition queries** (`what is`, `define`, `list`) → PostgreSQL full-text keyword search — faster and more precise for factual lookups
- **Semantic / complex questions** → pgvector HNSW vector search with MMR reranking

This saves tokens, reduces latency, and improves answer quality for different query types.

---

## Architecture Overview

```
User Query
    │
    ▼
QueryComplexityAnalyzer
    │
    ├── GENERAL_LLM ──────────────────────► Direct LLM response
    │
    ├── KEYWORD_SEARCH ───────────────────► PostgreSQL FTS
    │                                           │
    └── VECTOR_SEARCH ───────────────────► pgvector HNSW
                                               │
                                         MMR Reranker (λ=0.7)
                                               │
                                      Relevance Grader (LLM)
                                               │
                                    ┌── RELEVANT ──► LLM Answer
                                    │
                                    └── NOT RELEVANT ──► Query Rewriter
                                                             │
                                                        Retry once
                                                             │
                                                    ┌── RELEVANT ──► LLM Answer
                                                    └── FAIL ──► Fallback message
```

**Document ingestion flow:**

```
PDF Upload
    │
    ▼
S3 Storage (storageKey saved to DB)
    │
    ▼
Async Worker Thread
    │
    ├── PDF text extraction (PdfExtractor)
    ├── Text chunking (800 tokens, 150 overlap)
    ├── Embedding via Ollama (nomic-embed-text, 1536 dims)
    └── pgvector HNSW insert (m=16, ef=64)
```

---

## Tech Stack

### Backend
| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Auth | Spring Security + JWT (access + refresh tokens) |
| Database | PostgreSQL + pgvector extension |
| Caching | Redis (spend cap counters, query cache) |
| Object Storage | AWS S3 (LocalStack for local dev) |
| LLM | Groq API — `llama-3.3-70b-versatile` (answers), `llama-3.1-8b-instant` (grading/rewriting) |
| Embeddings | Ollama — `nomic-embed-text` (1536 dims, local, free) |
| Resilience | Resilience4j — circuit breakers + retry + rate limiting |
| Observability | Micrometer + Prometheus + Grafana + Jaeger (distributed tracing) |
| DB Migrations | Flyway |
| Build | Maven |

### Frontend
| Layer | Technology |
|-------|-----------|
| Framework | Next.js 14 (App Router) |
| Language | TypeScript |
| State | Zustand + TanStack Query |
| Forms | React Hook Form + Zod |
| Charts | Recharts |
| HTTP | Axios |
| Styling | Tailwind CSS + clsx |

---

## Key Engineering Decisions

### 1. MMR Reranking (Maximal Marginal Relevance)
Without reranking, the top-K retrieved chunks often cover the same sub-topic. MMR balances relevance and diversity:

```
score = λ × relevance(chunk, query) − (1 − λ) × max_similarity(chunk, selected_chunks)
```

With λ=0.7, relevance is weighted more, but chunks too similar to already-selected ones are penalized. The LLM gets richer, more complete context.

### 2. Self-Correcting Retrieval Loop
After retrieval, a lightweight LLM call grades whether the retrieved context actually answers the question. If not, the query is semantically rewritten and retrieval retries once. This handles poorly-phrased queries without hallucinating answers.

### 3. Atomic Spend Cap via Redis
The naive approach — read DB total, compare to cap, proceed — is not atomic. Two concurrent requests can both read the same spend total and both pass the check together.

Fix: Redis `INCRBYFLOAT` with a 25-hour TTL key. The check and increment are atomic at the Redis level. If Redis is unavailable, the system falls back to a DB check. The cap is always enforced.

Key format: `rag:spend:{userId}:{yyyy-MM-dd}`

### 4. Async Ingestion with S3
PDF text extraction is CPU-bound. Running it on the HTTP request thread would add latency to the upload response. Instead, the HTTP thread uploads bytes to S3 and immediately returns `PENDING`. An async worker thread downloads from S3, extracts text, chunks it, embeds it, and writes to pgvector — all without blocking the user.

### 5. HyDE (Hypothetical Document Embeddings)
For complex queries, a hypothetical answer is generated first, then embedded. The hypothetical answer's embedding is closer to real document chunk embeddings than the raw query embedding — improving retrieval recall for semantic questions.

---

## Project Structure

```
Adaptive-rag/
└── rag-final/
    ├── src/main/java/com/ai/rag/
    │   ├── controller/
    │   │   ├── RagController.java          # /ask, /history, /cost endpoints
    │   │   ├── StreamingRagController.java # SSE streaming endpoint
    │   │   ├── DocumentController.java     # Upload, list, delete documents
    │   │   └── AuthController.java         # Signup, login, refresh, logout
    │   ├── service/
    │   │   ├── impl/
    │   │   │   ├── RagPipelineServiceImpl.java    # Core RAG pipeline
    │   │   │   ├── IngestionServiceImpl.java      # Async PDF ingestion
    │   │   │   └── DocumentServiceImpl.java       # Document management
    │   │   ├── SpendCapService.java               # Redis-backed spend cap
    │   │   ├── ConversationMemoryService.java     # Chat history
    │   │   └── OpenAiClient.java                  # Groq + Ollama client
    │   ├── util/
    │   │   ├── QueryComplexityAnalyzer.java       # Query routing logic
    │   │   ├── MmrReranker.java                   # MMR reranking
    │   │   ├── TextChunker.java                   # Chunking strategy
    │   │   ├── PdfExtractor.java                  # PDF text extraction
    │   │   └── HydeGenerator.java                 # Hypothetical doc embeddings
    │   ├── resilience/
    │   │   └── RateLimiterService.java            # Token bucket rate limiting
    │   ├── storage/
    │   │   └── S3FileStorageService.java          # S3 abstraction
    │   ├── config/                                # Spring configs
    │   ├── entity/                                # JPA entities
    │   ├── repository/                            # Spring Data repos
    │   ├── dto/                                   # Request/response DTOs
    │   └── exception/                             # Global exception handling
    ├── src/test/java/com/ai/rag/integration/
    │   ├── AuthIntegrationTest.java
    │   ├── RagPipelineIntegrationTest.java
    │   ├── VectorSearchIntegrationTest.java
    │   └── CircuitBreakerIntegrationTest.java
    ├── docker-compose.yml
    ├── prometheus.yml
    ├── alerts.yml
    └── alertmanager.yml

adaptive-rag-frontend/
└── src/
    ├── app/
    │   ├── dashboard/
    │   │   ├── chat/         # Main Q&A interface
    │   │   ├── documents/    # Document upload & management
    │   │   ├── knowledge/    # Knowledge base view
    │   │   ├── retrieval/    # Retrieval debug view
    │   │   └── analytics/    # Usage & cost analytics
    │   ├── login/
    │   └── signup/
    ├── components/
    │   ├── auth/
    │   └── layout/
    └── api/                  # Axios API clients
```

---

## API Endpoints

### Auth
```
POST /api/auth/signup       Register new user
POST /api/auth/login        Login, returns access + refresh tokens
POST /api/auth/refresh      Refresh access token
POST /api/auth/logout       Invalidate refresh token
```

### Documents
```
POST   /api/documents/ingest    Upload PDF (multipart/form-data)
GET    /api/documents           List all documents for user
GET    /api/documents/{id}      Get document by ID
DELETE /api/documents/{id}      Delete document + chunks
```

### RAG / Q&A
```
POST /api/rag/ask               Synchronous Q&A
POST /api/rag/ask/stream        Streaming Q&A (SSE)
GET  /api/rag/history           Paginated query history
GET  /api/rag/history/{id}      Single query history entry
GET  /api/rag/cost/current-month   Current month spend summary
```

---

## Local Setup

### Prerequisites
- Java 21
- Maven 3.9+
- Docker + Docker Compose
- [Ollama](https://ollama.com) installed on your host machine
- Free [Groq API key](https://console.groq.com) (no credit card required)

### Step 1 — Start Ollama and pull embedding model
```bash
ollama pull nomic-embed-text
ollama serve
```
Ollama must run on your host machine (not in Docker) for GPU access.

### Step 2 — Clone the repos
```bash
git clone <backend-repo-url>
git clone <frontend-repo-url>
```

### Step 3 — Configure environment

Create `.env` in the backend root (`rag-final/`):
```env
# Database
DB_USERNAME=raguser
DB_PASSWORD=ragpassword

# Redis
REDIS_PASSWORD=redispassword

# JWT (generate a strong random string, min 32 chars)
JWT_SECRET=your-secret-key-here-minimum-32-characters

# Groq API key (free from console.groq.com)
OPENAI_API_KEY=gsk_your_groq_key_here

# AWS S3 (use test values for local dev with LocalStack)
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
S3_BUCKET=adaptive-rag-files
AWS_REGION=us-east-1

# For LocalStack (local S3 mock)
S3_ENDPOINT_OVERRIDE=http://localhost:4566

# Ollama host (macOS/Windows)
OLLAMA_HOST=http://host.docker.internal:11434
# Linux: use your docker0 bridge IP, e.g.:
# OLLAMA_HOST=http://172.17.0.1:11434
```

Create `.env.local` in the frontend root:
```env
NEXT_PUBLIC_API_URL=http://localhost:8080
```

### Step 4 — Start infrastructure
```bash
# Core services (Postgres, Redis, app)
docker compose up -d

# With local S3 mock (LocalStack)
docker compose --profile dev up -d

# With full monitoring stack (Prometheus, Grafana, Jaeger)
docker compose --profile monitoring up -d
```

### Step 5 — Start frontend
```bash
cd adaptive-rag-frontend
npm install
npm run dev
```

Frontend runs at `http://localhost:3000`
Backend runs at `http://localhost:8080`

---

## Monitoring & Observability

| Service | URL | Credentials |
|---------|-----|-------------|
| App health | `http://localhost:8080/actuator/health` | — |
| Prometheus metrics | `http://localhost:8080/actuator/prometheus` | — |
| Prometheus UI | `http://localhost:9090` | — |
| Grafana dashboards | `http://localhost:3000` | admin / admin |
| Jaeger tracing | `http://localhost:16686` | — |

Custom metrics exposed:
- `rag.stream.persist.failure` — streaming cost DB persistence failures
- `rag.query.route` — query routing distribution (GENERAL_LLM / KEYWORD / VECTOR)
- Circuit breaker state for Groq and Ollama

---

## Running Tests

```bash
cd rag-final
mvn test
```

Integration tests use Testcontainers and spin up real PostgreSQL + Redis containers. Requires Docker running.

Test coverage:
- `AuthIntegrationTest` — signup, login, token refresh, logout flows
- `RagPipelineIntegrationTest` — end-to-end Q&A pipeline
- `VectorSearchIntegrationTest` — pgvector HNSW indexing and similarity search
- `CircuitBreakerIntegrationTest` — Resilience4j circuit breaker behavior

---

## What I'd Improve Next

- **Query routing**: Replace the regex-based `QueryComplexityAnalyzer` with a lightweight few-shot prompt classifier or a fine-tuned model. The current regex is brittle on edge cases.
- **Chunk strategy**: Implement semantic chunking (split on paragraph/section boundaries) instead of fixed token windows. Fixed chunking can cut sentences mid-thought.
- **Reranking**: Add a cross-encoder reranker (e.g., `ms-marco-MiniLM`) as a second stage after MMR. Currently disabled via config flag.
- **Multi-tenancy**: Namespace pgvector queries by user to support team/organization document isolation.
- **Streaming spend cap**: The current approach reserves spend post-response. Pre-request token estimation with a buffer would prevent edge-case cap overruns on very long responses.

---

## Author

**Divyanshu Tiwari**
- GitHub: [github.com/divyanshutiwari](https://github.com/Divyanshutiwari102)
- LinkedIn: [linkedin.com/in/divyanshutiwari](https://linkedin.com/in/](https://www.linkedin.com/in/divyanshu-tiwari-42b156289/)
- Email: divyanshutiwari337@gmail.com
