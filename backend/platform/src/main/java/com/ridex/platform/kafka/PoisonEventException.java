package com.ridex.platform.kafka;

/**
 * A message that can never be processed, however often it is tried (broken JSON, missing fields). It is not
 * retried: it goes straight to the dead-letter topic, so it cannot block the messages behind it.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public PoisonEventException(String message) {
        super(message);
    }
}
