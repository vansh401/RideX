package com.ridex.platform.web;

import java.time.Instant;
import java.util.Map;

/** The one JSON error shape of every RideX service (the same as the core application). */
public record ApiError(Instant timestamp, int status, String error, String message, String path, Map<String, String> fieldErrors) {
}
