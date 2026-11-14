# ChildGuard Server — Rust

The parent collector server, rewritten in **Rust** (pure `std` + `serde_json`,
no web framework, no external services). Single static binary, tiny attack
surface, constant-time auth, bounded state and connections.

## Why Rust

- memory-safe by construction (no buffer overflows / use-after-free)
- one static binary, no runtime / no `npm install`
- hardened against malformed input from untrusted phones
- same API contract as the previous Node server — the Android app and the
  Termux dashboard work unchanged

## Build

```bash
cargo build --release
# binary: target/release/childguard-server
```

## Run

```bash
CHILDGUARD_TOKEN=your-secret ./target/release/childguard-server
# default: 0.0.0.0:38080
# env: PORT, BIND, CHILDGUARD_TOKEN
```

## Endpoints (same as before)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | liveness + token hint |
| POST | `/api/register` | Bearer | register a child, get `childId` |
| POST | `/api/events` | Bearer | ingest events from the app |
| GET | `/api/stats` | Bearer | summary, children, last alerts |
| GET | `/api/alerts?limit=N` | Bearer | recent alerts |
| GET | `/api/events?limit=N` | Bearer | recent events |
| GET | `/api/export` | Bearer | full snapshot (events+alerts+stats) |
| GET | `/api/stream` | Bearer | SSE live stream |

## Security notes

- Bearer token compared in **constant time** (no timing side channel)
- request body capped at 2 MiB, header scan bounded, timeouts on sockets
- malformed / non-UTF8 JSON rejected with 400
- unknown `childId` auto-registers (events are never dropped)
- events/alerts capped in memory (5000 / 500), oldest evicted
- at most 256 concurrent connections, 503 when saturated

## systemd unit

See `childguard-server.service` (adjust paths).

## Tests

```bash
cargo test
```
Covers auth, register→events flow, auto-register, malformed input, caps, query parsing.
