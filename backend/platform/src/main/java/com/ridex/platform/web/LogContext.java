package com.ridex.platform.web;

import org.slf4j.MDC;

/**
 * What every log line should say about the work in progress, besides the message. These values go into the logging context (MDC), and the
 * JSON log format writes each one as its own field, so "show me everything that happened to ride X" is a search, not a guess.
 *
 *   requestId  set by RequestIdFilter     one id across the gateway and all services for one HTTP request
 *   traceId    set by the tracing library  one id across HTTP calls AND events published to Kafka
 *   userId     set after authentication    who is acting (an id, never a name, email or token)
 *   rideId     set by the code that works on a ride
 *
 * NEVER put in a log line or in this context: passwords, tokens or keys, one-time codes, card or bank data, full email addresses or phone
 * numbers, or the body of a request. Ids are fine. (LogHygieneTest checks the obvious ones.)
 */
public final class LogContext {

    public static final String USER_ID = "userId";
    public static final String RIDE_ID = "rideId";

    private LogContext() {
    }

    /** Puts a value in the logging context until the returned scope is closed, then restores what was there before. */
    public static Scope with(String key, Object value) {
        String previous = MDC.get(key);
        if (value != null) {
            MDC.put(key, value.toString());
        }
        return () -> {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        };
    }

    /** "asha.rao@example.com" becomes "a***@example.com": enough to tell which account in a support case, useless to a thief. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    public static Scope ride(Object rideId) {
        return with(RIDE_ID, rideId);
    }

    /** A scope that cannot throw on close, so it can sit in try-with-resources without a catch. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
