# Rate Limiter (Java 21)

An in-memory, thread-safe rate limiter for an LLM API gateway, built as a LeetCode Project.

## Policies

| Strategy | Algorithm | Parameters |
|---|---|---|
| `policy_a` | Sliding window log: keeps timestamps of accepted requests per `(clientId, endpoint)` and counts only those inside the last `windowMs` | `limit`, `windowMs` |
| `policy_b` | Token bucket: each key starts with `capacity` tokens, refilled continuously at `refillRate` tokens/second; each accepted request spends one token | `capacity`, `refillRate` |

Each call returns `Decision(allowed, remaining, resetAfterMs)`:

- **Sliding window:** `remaining = limit - counted`, and `resetAfterMs` is the time until the oldest counted request leaves the window.
- **Token bucket:** `remaining` is the number of whole tokens left. `resetAfterMs` is `0` on accept; on reject it is the time until one full token is available.

`stats()` returns `Stats(strategy, totalAllowed, totalRejected, activeKeys)`.

## Concurrency

Every decision (state refresh, accept/reject, state mutation, counter update) runs under one lock, so decisions and statistics are always consistent. A monotonic clock (`System.nanoTime`) is used so wall-clock adjustments can't break the windows.

## Scaling notes

- **Single machine, many threads:** stripe locks per key (`ConcurrentHashMap` + per-state locks) and use `LongAdder` for counters.
- **Many instances:** move state to Redis and make each check one atomic Lua script (sorted set for the sliding window, hash for the token bucket) with TTLs and the server clock.

## Layout

```
src/main/java/ratelimiter/
  RateLimiter.java   // both policies
  Decision.java      // check() result
  Stats.java         // stats() result
```
