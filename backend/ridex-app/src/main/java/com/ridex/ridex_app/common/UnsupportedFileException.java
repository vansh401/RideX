package com.ridex.ridex_app.common;

/** The uploaded file is empty, too large or not an accepted format. Mapped to HTTP 400. */
public class UnsupportedFileException extends RuntimeException {

    public UnsupportedFileException(String message) {
        super(message);
    }
}
