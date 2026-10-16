## 1. Contract and package
- [x] 1.1 Confirm eight Agent operations, two vector dependencies and existing table boundaries.
- [x] 1.2 Add installable Python Agent package and pinned create_agent dependencies.

## 2. Implementation
- [x] 2.1 Implement Backend AI history, current authorization, stable IDs and bound pagination.
- [x] 2.2 Implement readonly tools, fixed scope, structured planning/output and tool-read ledger.
- [x] 2.3 Implement async run lifecycle, stop/timeout, atomic completion and exclusive restart recovery.
- [x] 2.4 Implement configurable chat and embedding adapters and the existing pgvector operations.
- [x] 2.5 Compose routes in the existing API application, and document local configuration and failure behavior.

## 3. Verification
- [x] 3.1 Test actual create_agent tools with a deterministic test model, including forged references and bounds.
- [x] 3.2 Test real PostgreSQL history, authorization/revocation, concurrent retry/stop/completion and restart.
- [x] 3.3 Validate output against current OpenAPI and verify prior Backend regressions.
- [x] 3.4 Record tested input provenance, model-service limits and checks without claiming external-model success.
