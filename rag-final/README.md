# Adaptive RAG — Free Version (Groq + Ollama)

Zero cost. No credit card. Production-grade.

**Chat:** Groq (llama-3.3-70b) · **Embeddings:** Ollama (nomic-embed-large) · **DB:** PostgreSQL + pgvector · **Cache:** Redis

---

## Project Structure

```
rag-final/
├── src/
│   ├── main/
│   │   ├── java/com/ai/rag/
│   │   │   ├── config/
│   │   │   │   ├── AsyncConfig.java
│   │   │   │   ├── CacheConfig.java          (Redis cache, error handler)
│   │   │   │   ├── OpenAiProperties.java     (Groq config)
│   │   │   │   ├── RagProperties.java
│   │   │   │   ├── RateLimitConfig.java
│   │   │   │   ├── RedisConfig.java          (Bucket4j Lettuce client)
│   │   │   │   ├── S3Config.java
│   │   │   │   ├── StorageProperties.java
│   │   │   │   └── WebClientConfig.java      ← UPDATED (Groq + Ollama clients)
│   │   │   ├── controller/
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── DocumentController.java
│   │   │   │   ├── RagController.java
│   │   │   │   └── StreamingRagController.java
│   │   │   ├── dto/
│   │   │   │   ├── AskRequest.java
│   │   │   │   ├── AskResponse.java
│   │   │   │   ├── CostSummary.java
│   │   │   │   ├── LoginRequest.java
│   │   │   │   ├── RefreshRequest.java
│   │   │   │   ├── SignupRequest.java
│   │   │   │   └── TokenResponse.java
│   │   │   ├── entity/
│   │   │   │   ├── Document.java
│   │   │   │   ├── DocumentChunk.java
│   │   │   │   ├── QueryHistory.java
│   │   │   │   ├── RefreshToken.java
│   │   │   │   └── User.java
│   │   │   ├── enums/
│   │   │   │   └── QueryRoute.java
│   │   │   ├── exception/
│   │   │   │   ├── FileSanitizationException.java
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── InvalidTokenException.java
│   │   │   │   ├── RagProcessingException.java
│   │   │   │   ├── RateLimitExceededException.java
│   │   │   │   └── ResourceNotFoundException.java
│   │   │   ├── filter/
│   │   │   │   └── TraceIdFilter.java
│   │   │   ├── metrics/
│   │   │   │   └── RagMetrics.java
│   │   │   ├── repository/
│   │   │   │   ├── DocumentChunkRepository.java
│   │   │   │   ├── DocumentRepository.java
│   │   │   │   ├── QueryHistoryRepository.java
│   │   │   │   ├── RefreshTokenRepository.java
│   │   │   │   └── UserRepository.java
│   │   │   ├── resilience/
│   │   │   │   └── RateLimiterService.java
│   │   │   ├── security/
│   │   │   │   ├── JwtAuthenticationFilter.java
│   │   │   │   ├── JwtUtil.java
│   │   │   │   ├── RefreshTokenService.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── TenantContext.java
│   │   │   ├── service/
│   │   │   │   ├── ConversationMemoryService.java
│   │   │   │   ├── DocumentService.java
│   │   │   │   ├── IngestionService.java
│   │   │   │   ├── OpenAiClient.java         ← UPDATED (Groq chat + Ollama embed)
│   │   │   │   ├── PartitionMaintenanceService.java
│   │   │   │   ├── RagPipelineService.java
│   │   │   │   ├── SpendCapService.java
│   │   │   │   └── impl/
│   │   │   │       ├── DocumentServiceImpl.java
│   │   │   │       ├── IngestionServiceImpl.java
│   │   │   │       └── RagPipelineServiceImpl.java
│   │   │   ├── storage/
│   │   │   │   ├── FileStorageService.java
│   │   │   │   ├── LocalFileStorageService.java
│   │   │   │   └── S3FileStorageService.java
│   │   │   └── util/
│   │   │       ├── EmbeddingCacheKeyUtil.java
│   │   │       ├── MmrReranker.java
│   │   │       ├── PdfExtractor.java
│   │   │       ├── PromptSanitizer.java
│   │   │       ├── QueryComplexityAnalyzer.java
│   │   │       ├── TextChunker.java
│   │   │       └── VectorSearchHelper.java
│   │   └── resources/
│   │       ├── application.yml               ← UPDATED (Groq + Ollama config)
│   │       └── db/migration/
│   │           ├── V1__initial_schema.sql
│   │           ├── V2__add_pgvector.sql      (vector(1536) — unchanged, compatible)
│   │           ├── V3__indexes_and_session.sql
│   │           ├── V4__async_ingestion_fields.sql
│   │           ├── V5__row_level_security_base.sql
│   │           ├── V6__partition_query_history.sql
│   │           ├── V7__rls_initial_policies.sql
│   │           └── V8__rls_restrictive_and_fts_gin_index.sql
│   └── test/
│       ├── java/com/ai/rag/
│       └── resources/
│           └── application-test.yml
├── alerts.yml
├── alertmanager.yml
├── docker-compose.yml                        ← UPDATED (Ollama host config)
├── Dockerfile
├── prometheus.yml
├── pom.xml
├── .env.example                              ← UPDATED (Groq key instructions)
└── README.md
```

---

## What Changed from Paid Version

| Component | Before (Paid) | After (Free) |
|-----------|--------------|--------------|
| Chat model | `gpt-4o` | `llama-3.3-70b-versatile` via Groq |
| Mini model | `gpt-4o-mini` | `llama-3.1-8b-instant` via Groq |
| Embeddings | OpenAI `text-embedding-3-small` | Ollama `nomic-embed-large` |
| Embedding dims | 1536 | 1536 (identical — no DB change) |
| Chat cost | ~$0.005–$0.015/1K tokens | $0.00 |
| Embed cost | $0.00002/1K tokens | $0.00 |
| `WebClientConfig` | 1 WebClient bean | 2 beans (Groq + Ollama) |
| `OpenAiClient` | Single WebClient | Groq for chat, Ollama for embed |
| DB schema | unchanged | unchanged |
| All other code | unchanged | unchanged |

---

## Step-by-Step Run Instructions

### Step 1 — Install Ollama

Ollama runs **on your host machine** (not in Docker). It handles all embeddings locally.

```bash
# macOS
brew install ollama

# Linux
curl -fsSL https://ollama.com/install.sh | sh

# Windows
# Download the installer from https://ollama.com/download
```

### Step 2 — Pull the Embedding Model

```bash
ollama pull nomic-embed-large
```

This downloads ~670 MB once. After that it's cached and instant.

Why `nomic-embed-large`? It produces **exactly 1536 dimensions** — the same as OpenAI `text-embedding-3-small`. This means the pgvector schema (`vector(1536)`) works without any database changes.

### Step 3 — Start Ollama

```bash
ollama serve
```

Keep this terminal open. Ollama listens on `http://localhost:11434`. You should see:
```
Ollama is running
```

Verify it works:
```bash
curl http://localhost:11434/api/tags
# Should return JSON listing nomic-embed-large
```

### Step 4 — Get a Free Groq API Key

1. Go to https://console.groq.com
2. Sign up (no credit card required)
3. Go to **API Keys** → **Create API Key**
4. Copy the key (starts with `gsk_...`)

Free limits:
- `llama-3.3-70b-versatile`: 6,000 tokens/min, 500 requests/day
- `llama-3.1-8b-instant`: 30,000 tokens/min, 14,400 requests/day

### Step 5 — Set Up .env

```bash
cp .env .env
```

Edit `.env` and fill in:

```bash
# Your Groq API key (not OpenAI)
OPENAI_API_KEY=gsk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Generate JWT secret
JWT_SECRET=$(openssl rand -base64 48)

# DB credentials (choose anything)
DB_USERNAME=raguser
DB_PASSWORD=ragpassword123

# Redis password (choose anything)
REDIS_PASSWORD=redispass123

# Ollama host — where the app container can reach Ollama on your machine
# macOS/Windows (Docker Desktop):
OLLAMA_HOST=http://host.docker.internal:11434

# Linux — use your docker bridge IP instead:
# OLLAMA_HOST=http://172.17.0.1:11434
```

To find your Linux docker bridge IP:
```bash
ip route | grep docker0 | awk '{print $9}'
# Usually 172.17.0.1
```

### Step 6 — Build the Java Application

```bash
mvn clean package -DskipTests
```

Expected output ending in:
```
BUILD SUCCESS
```

### Step 7 — Start Docker Services (Postgres + Redis + App)

```bash
docker compose up -d
```

Watch the startup logs — Flyway runs 8 migrations on first boot:
```bash
docker compose logs -f app
```

Wait for:
```
Started AdaptiveRagApplication in X.XXX seconds
```

This takes about 60–90 seconds on first run (Flyway migrations).

### Step 8 — One-Time Database RLS Setup

Run this once after Postgres first starts:

```bash
docker compose exec postgres psql -U raguser -d adaptive_rag
```

Inside psql:
```sql
CREATE ROLE rag_migrator WITH BYPASSRLS NOLOGIN;
GRANT rag_migrator TO raguser;
ALTER ROLE raguser NOBYPASSRLS;
ALTER ROLE raguser SET app.current_user_id = '';
\q
```

### Step 9 — Verify Everything is Running

```bash
# App health check
curl http://localhost:8080/actuator/health

# Expected:
# {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}

# Test Ollama from inside the app container
docker compose exec app wget -qO- http://host.docker.internal:11434/api/tags
# Should return JSON with nomic-embed-large listed
```

### Step 10 — Test the Full Flow

**Register:**
```bash
curl -X POST http://localhost:8080/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","password":"SecurePass123!"}'
```

**Login:**
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"SecurePass123!"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "Token: $TOKEN"
```

**Upload a document:**
```bash
curl -X POST http://localhost:8080/api/documents/ingest \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/your/document.pdf" \
  -F "description=My test document"
```

**Poll until DONE** (replace UUID):
```bash
curl http://localhost:8080/api/documents/<uuid> \
  -H "Authorization: Bearer $TOKEN" | grep ingestionStatus
```

**Ask a question:**
```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"What is this document about?"}'
```

**Streaming:**
```bash
curl -N -X POST http://localhost:8080/api/rag/ask/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"Summarize the key points"}'
```

---

## Optional: Monitoring Stack

```bash
docker compose --profile monitoring up -d
```

| Service | URL | Login |
|---------|-----|-------|
| Prometheus | http://localhost:9090 | none |
| Grafana | http://localhost:3000 | admin / admin |
| Jaeger | http://localhost:16686 | none |
| Alertmanager | http://localhost:9093 | none |

---

## Troubleshooting

**"Ollama embedding failed — Is Ollama running?"**
```bash
# Check Ollama is running
curl http://localhost:11434/api/tags

# If not running:
ollama serve

# If model not pulled:
ollama pull nomic-embed-large
```

**"Connection refused" to Ollama from Docker (Linux)**
```bash
# Find your docker bridge IP
ip route | grep docker0 | awk '{print $9}'

# Update .env
OLLAMA_HOST=http://172.17.0.1:11434   # use your actual IP

# Restart app
docker compose restart app
```

**"Groq rate limit reached"**
You've hit the 500 req/day limit on `llama-3.3-70b`. Options:
- Wait until tomorrow (resets at midnight UTC)
- Switch to `llama-3.1-8b-instant` temporarily (14,400 req/day limit) by editing `application.yml`:
  ```yaml
  chat-model: llama-3.1-8b-instant
  ```

**"Flyway migration checksum mismatch"**
A migration file was changed after running. Reset:
```bash
docker compose down -v
docker compose up -d postgres redis
# Wait for healthy, then run RLS setup again (Step 8)
docker compose up -d app
```

**App starts but embeddings return wrong dimensions**
Verify nomic-embed-large outputs 1536 dims:
```bash
curl http://localhost:11434/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"nomic-embed-large","input":"test"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['embeddings'][0]))"
# Must print: 1536
```

---

## Stop / Reset

```bash
# Stop everything, keep data
docker compose down

# Stop and delete ALL data (full reset)
docker compose down -v
```

---

## Free Tier Limits Summary

| Provider | Model | Limit | Used For |
|----------|-------|-------|----------|
| Groq | llama-3.3-70b-versatile | 500 req/day, 6K tok/min | Final answers |
| Groq | llama-3.1-8b-instant | 14,400 req/day, 30K tok/min | Grade, rewrite, HyDE |
| Ollama | nomic-embed-large | Unlimited (local) | All embeddings |

For a personal project or development, these limits are generous. At 500 requests/day on the main model, that's hundreds of full RAG queries daily.
