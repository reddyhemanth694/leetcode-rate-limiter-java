package ratelimiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory rate limiter supporting two policies:
 * policy_a = sliding window log, policy_b = token bucket.
 * All state changes and statistics updates happen under a single lock,
 * so every decision is atomic with respect to the counters.
 */
public class RateLimiter {
    private record Key(String clientId, String endpoint) {}

    /** Sliding window state: timestamps (ms) of accepted requests, oldest first. */
    private static final class Window {
        final Deque<Long> accepted = new ArrayDeque<>();
    }

    /** Token bucket state. */
    private static final class Bucket {
        double tokens;
        double lastRefillMs;

        Bucket(double tokens, double nowMs) {
            this.tokens = tokens;
            this.lastRefillMs = nowMs;
        }
    }

    private final String strategy;
    private final Object lock = new Object();
    private final Map<Key, Object> state = new HashMap<>();

    private long totalAllowed;
    private long totalRejected;

    public RateLimiter(String strategy) {
        if (!"policy_a".equals(strategy) && !"policy_b".equals(strategy)) {
            throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
        this.strategy = strategy;
    }

    public Decision check(
            String clientId,
            String endpoint,
            int limit,
            long windowMs,
            int capacity,
            double refillRate) {
        synchronized (lock) {
            Decision result =
                    strategy.equals("policy_a")
                            ? policyALimit(clientId, endpoint, limit, windowMs)
                            : policyBLimit(clientId, endpoint, capacity, refillRate);
            if (result.allowed()) {
                totalAllowed++;
            } else {
                totalRejected++;
            }
            return result;
        }
    }

    /** Monotonic clock in fractional milliseconds. */
    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    /** Policy A: sliding window log over the last windowMs milliseconds. */
    private Decision policyALimit(String clientId, String endpoint, int limit, long windowMs) {
        long now = (long) nowMs();
        Window w = (Window) state.computeIfAbsent(new Key(clientId, endpoint), k -> new Window());
        Deque<Long> q = w.accepted;
        while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
            q.pollFirst();
        }
        boolean allowed = q.size() < limit;
        if (allowed) {
            q.addLast(now);
        }
        int remaining = Math.max(0, limit - q.size());
        long resetAfterMs = q.isEmpty() ? 0 : Math.max(0, q.peekFirst() + windowMs - now);
        return new Decision(allowed, remaining, resetAfterMs);
    }

    /** Policy B: token bucket with continuous refill at refillRate tokens per second. */
    private Decision policyBLimit(String clientId, String endpoint, int capacity, double refillRate) {
        double now = nowMs();
        Bucket b = (Bucket) state.computeIfAbsent(new Key(clientId, endpoint), k -> new Bucket(capacity, now));
        double elapsed = Math.max(0, now - b.lastRefillMs);
        if (refillRate > 0) {
            b.tokens = Math.min(capacity, b.tokens + elapsed * refillRate / 1000.0);
        }
        b.tokens = Math.min(b.tokens, capacity);
        b.lastRefillMs = now;
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0;
            return new Decision(true, (int) Math.floor(b.tokens + 1e-9), 0);
        }
        long resetAfterMs = refillRate > 0 ? (long) ((1.0 - b.tokens) / refillRate * 1000.0) : 0;
        return new Decision(false, 0, Math.max(0, resetAfterMs));
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(strategy, totalAllowed, totalRejected, state.size());
        }
    }
}
