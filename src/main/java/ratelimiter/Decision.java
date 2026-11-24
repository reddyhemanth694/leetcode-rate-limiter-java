package ratelimiter;

/**
 * Result of a single rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param remaining requests that can still be accepted right away for the same (clientId, endpoint)
 *     after this decision; 0 when rejected
 * @param resetAfterMs non-negative milliseconds until capacity becomes available again
 */
public record Decision(boolean allowed, int remaining, long resetAfterMs) {}
