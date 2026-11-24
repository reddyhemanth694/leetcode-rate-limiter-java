package ratelimiter;

/**
 * Aggregate statistics returned by {@link RateLimiter#stats()}.
 *
 * @param activeKeys tracked (clientId, endpoint) entries for the active policy
 */
public record Stats(String strategy, long totalAllowed, long totalRejected, int activeKeys) {}
