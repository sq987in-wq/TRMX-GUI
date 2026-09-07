# Contract Fixtures — normative

These fixtures are the **shared source of truth** for the TRMX-P/1 wire contract ([docs/PROTOCOL.md](../docs/PROTOCOL.md)).

Rules:
1. The Android test suite (Kotlin, Phase 4+) and the bridge test suite (Python `unittest`, Phase 2+) **both** load and validate these exact files. A change on either side that drifts from a fixture is a CI failure.
2. Fixtures change only together with `docs/PROTOCOL.md`, an ADR, and a `CHANGELOG.md` entry — same commit (PROTOCOL §13).
3. `.json` fixtures are exact response/request shapes; `.txt` fixtures are byte-exact SSE stream samples (blank lines matter).
4. Unknown-field tolerance is part of the contract: validators ignore fields not present here, and must reject wrong *types* for known fields.
