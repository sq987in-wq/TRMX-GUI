# TRMX AI backend configuration (`~/.trmx/ai.json`)

`trmx-ai` (the AI Schema Builder backend, ADR-014) supports **two backend
modes**. The same untrusted-output validation applies to both — the
backend is interchangeable, the safety rules are not.

| Mode | Use when | Cost |
|---|---|---|
| `"cli"` | offline / local device, privacy-first | needs a local model runner (multi-GB models on device storage) |
| `"http_api"` | you have internet and want lightweight calls | needs an API key; prompts leave the device |

The file is written automatically (mode `cli`, documented template) the
first time `trmx-ai` runs without one. Keep it private — it may hold an
API key: `chmod 600 ~/.trmx/ai.json` (the wrapper does this for the
template it creates).

---

## Mode 1 — offline / local (`cli`)

Any CLI-wrapped model runner. The prompt is appended to `command` as ONE
argv element — no shell involved.

```json
{
  "mode": "cli",
  "cli": {
    "command": ["ollama", "run", "llama3.2"],
    "timeout_s": 180
  }
}
```

- `command`: argv list. Ollama ships in Termux (`pkg install ollama`,
  then `ollama pull llama3.2`). Any CLI that takes a prompt as its last
  argument works.
- `timeout_s`: optional, default 180.

**Legacy form** (v0.1.0, still accepted): `{"command": [...], "timeout_s": 180}`
at the top level, no `mode` key.

---

## Mode 2 — cloud API (`http_api`)

Lightweight HTTP calls via Python stdlib — no extra packages, no
multi-GB models on mobile storage.

```json
{
  "mode": "http_api",
  "http_api": {
    "provider": "groq",
    "endpoint": "",
    "api_key": "",
    "api_key_env": "GROQ_API_KEY",
    "model": "llama-3.3-70b-versatile",
    "timeout_s": 120
  }
}
```

### Fields

| Field | Required | Meaning |
|---|---|---|
| `provider` | yes | `openai` · `groq` · `gemini` · `openai_compatible` |
| `model` | yes | provider model id, e.g. `llama-3.3-70b-versatile` (groq), `gpt-4o-mini` (openai), `gemini-2.0-flash` (gemini) |
| `api_key` | one of | literal key. **Prefer `api_key_env`** so the key stays out of files |
| `api_key_env` | one of | environment variable to read the key from. If unset, the provider's conventional `OPENAI_API_KEY` / `GROQ_API_KEY` / `GEMINI_API_KEY` is tried |
| `endpoint` | optional | override the provider's default URL. **Required** for `openai_compatible` |
| `timeout_s` | optional | default 120 |

### Providers

| `provider` | Default endpoint | Auth | Wire shape |
|---|---|---|---|
| `groq` | `https://api.groq.com/openai/v1/chat/completions` | `Authorization: Bearer <key>` | OpenAI chat |
| `openai` | `https://api.openai.com/v1/chat/completions` | `Authorization: Bearer <key>` | OpenAI chat |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` | `x-goog-api-key: <key>` | Gemini `contents/parts` |
| `openai_compatible` | — (you must set `endpoint`) | `Authorization: Bearer <key>` | OpenAI chat |

`openai_compatible` covers the long tail of OpenAI-shaped APIs
(Together, Mistral, OpenRouter, a self-hosted llama.cpp server, …):
point `endpoint` at their `/chat/completions` URL.

### Minimal examples

Groq with the key in the environment (recommended):

```json
{ "mode": "http_api",
  "http_api": { "provider": "groq", "model": "llama-3.3-70b-versatile" } }
```
with `GROQ_API_KEY` exported (e.g. in `~/.bashrc`) — the conventional
variable is picked up automatically.

Gemini with a literal key:

```json
{ "mode": "http_api",
  "http_api": { "provider": "gemini", "model": "gemini-2.0-flash",
                "api_key": "AIza…" } }
```

Self-hosted llama.cpp server:

```json
{ "mode": "http_api",
  "http_api": { "provider": "openai_compatible",
                "endpoint": "http://127.0.0.1:8080/v1/chat/completions",
                "model": "local", "api_key": "none" } }
```

---

## Safety rules (both modes, enforced identically)

- LLM output is **untrusted data**: parsed, never eval'd; §7.2 structural
  validation (mirrored from the bridge) plus AI-specific caps; the
  wrapper writes nothing unless validation passes.
- AI-generated tools are pinned to `risk_tier: "confirm"` regardless of
  what the model claims — a human must edit `~/.trmx/tools/ai-*.json` to
  relax it.
- The wrapper never overwrites an existing schema file.
- API keys are sent only to the configured endpoint, and are never
  logged or echoed (URLs with `key=`-style query params are redacted in
  error messages).

## Switching / troubleshooting

- Switch mode by editing `"mode"` — both blocks can coexist in the file.
- `--model` overrides the configured model for one run (cli: ollama
  only; http_api: any provider).
- Exit codes: `0` ok · `2` usage · `3` backend/config/network problem
  (missing runner, bad JSON, missing key, HTTP error, bad provider) ·
  `4` LLM output rejected by validation · `5` refused overwrite.
- From the app, all of this is visible in the job's live output
  (Jobs → the ai-schema-builder job).
