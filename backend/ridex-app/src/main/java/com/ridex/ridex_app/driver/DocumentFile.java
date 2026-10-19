package com.ridex.ridex_app.driver;

/** A stored document ready to be sent to the caller. */
public record DocumentFile(String filename, String contentType, byte[] bytes) {
}
