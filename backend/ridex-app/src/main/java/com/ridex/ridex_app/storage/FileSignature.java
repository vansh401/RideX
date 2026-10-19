package com.ridex.ridex_app.storage;

import java.util.Optional;

/**
 * Works out what a file REALLY is from its first bytes ("magic numbers"), because the
 * Content-Type header and the file name are chosen by the client and can lie. Only the
 * formats we accept for documents are recognised.
 */
public final class FileSignature {

    public record Detected(String contentType, String extension) {
    }

    private FileSignature() {
    }

    public static Optional<Detected> detect(byte[] bytes) {
        if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(new Detected("application/pdf", "pdf"));
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(new Detected("image/jpeg", "jpg"));
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(new Detected("image/png", "png"));
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
