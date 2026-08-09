# Phase 2 Roadmap — GEO / AI Brand Visibility Tracker

This is intentionally a forward-looking narration doc, not implemented code. Phase 1
(shipped) makes the single-prompt search -> results flow accurate and stable: reliable
brand extraction, real DB persistence, correct parallel orchestration, and a consistent
frontend. Everything below is what comes next, and why it was deliberately deferred.

## 1. Semantic Prompt Cache (Vector DB)

**What:** Before dispatching a new prompt to Gemini/Groq, embed it and check a vector
store for a near-duplicate prompt already analyzed recently. On a high-similarity hit
(e.g. cosine similarity > 0.95), skip the LLM calls entirely and return the cached
analysis - this is the "Semantic Prompt Cache Check" step in the original target
architecture diagram.

**Why deferred in Phase 1:** No vector DB or embedding pipeline existed at all in the
codebase (the old code had a hardcoded fake log line pretending to check a cache that
didn't exist - that line has been removed). Building this well requires getting the
core extraction/persistence correctness right first; a cache in front of unreliable
data would just serve stale-and-wrong results faster.

**How it would be built:**
- Add `pgvector` extension to the existing PostgreSQL instance (simplest - no new
  infra - vs. standing up Pinecone/Weaviate as a separate service).
- New `PromptEmbedding` entity: `promptText`, `embedding vector(1536)`, `category`,
  `analysisResultId` (FK back to the cached `AnalysisData` snapshot), `createdAt`.
- Generate embeddings via the Gemini or OpenAI embeddings endpoint at request time
  (one extra network call, but cheap/fast compared to the full multi-LLM scatter-gather).
- `SemanticCacheService.findSimilar(promptEmbedding, category, threshold)` using
  pgvector's `<->` distance operator with a `SELECT ... ORDER BY embedding <-> :query
  LIMIT 1`.
- Cache invalidation policy: time-based TTL (e.g. 24-48h, since "best CRM tools"
  answers drift as the market/LLM training data changes) rather than never-expiring.
- Wire into `DynamicVisibilityService.analyzeCustomPrompt` as the very first step,
  short-circuiting the `CompletableFuture` scatter-gather on a hit.

## 2. Historical / Category Dashboard

**What:** A second dashboard view showing trends across *many* prompts in a tracked
category over time (e.g. "CRM Tools" category, showing how Share-of-Model for
Salesforce vs. HubSpot has moved over the last 30 days across dozens of tracked
prompts) - as opposed to Phase 1's single-prompt, single-search results view.

**Why deferred:** This backend path (`AnalysisService`, `DashboardData` DTO) already
exists in the codebase but has zero REST endpoint and zero frontend caller today -
it was speculative/unfinished work, not something currently in use. Wiring it up is a
real feature (new UI, new routing, category management) rather than a bug fix, so it's
scoped out of the stabilization pass.

**How it would be built:**
- New `AnalysisController` exposing `GET /api/analysis/dashboard/{categoryName}`,
  returning the existing `DashboardData` DTO (already shaped correctly - `leaderboard`,
  `brandMetrics`, `topCitedPages`, `modelComparison`, `prompts`).
- Fix the N+1 query pattern in `AnalysisService` first (currently re-queries
  `countByBrand`/`countByBrandAndAiModel` separately in 4 different methods for the
  same brand/model pairs) - replace with one grouped aggregate query and in-memory maps.
- Add `@Transactional(readOnly = true)` to `getDashboardData()`.
- Frontend: a new route (e.g. `/dashboard/:category`) and view with a category
  picker, trend charts over time (this is where `recharts`, removed in Phase 1 for
  being unused dead code, would come back deliberately), and a leaderboard table
  reusing the existing `Leaderboard` component pattern.
- Requires a real strategy for *how* prompts get into a tracked category repeatedly
  (currently nothing schedules recurring prompt runs) - likely a scheduled job
  (`@Scheduled`) that re-runs a fixed set of tracked prompts per category on a cadence,
  separate from the ad-hoc custom-prompt flow.

## 3. Other deliberately-scoped-out items

- **Multi-tenancy / auth**: today there's no user/account concept at all - every
  custom search lands in one shared `customSearch` category. A real product needs
  per-user or per-organization scoping on `Category`/`Brand`/`Prompt`.
- **Rate limiting & cost control**: no guard today against a user spamming
  `/api/visibility/analyze` and running up LLM API costs.
- **Observability**: no request tracing/metrics (e.g. Micrometer + Prometheus) around
  LLM call latency, cache hit rate (once built), or persistence failures - currently
  only SLF4J logs.
- **Testing**: no unit/integration test suite exists yet for either the extraction
  logic or the React components - worth adding once the shape of both stabilizes,
  rather than writing tests against code known to be getting rewritten.
- **CI/CD**: no pipeline currently runs builds/tests on push.

## Narrative summary (for interview)

"Phase 1 fixed correctness and reliability in the core loop - the thing a user
actually interacts with today. Phase 2 is about *scale* and *depth*: caching to cut
cost/latency on repeat queries, and a historical view to show trends over time instead
of just point-in-time snapshots. I scoped Phase 2 out deliberately rather than half-build
it, because the historical-dashboard backend code already existed unfinished and
unwired in the repo, and building semantic caching on top of unreliable extraction
would have meant caching bad data faster."
