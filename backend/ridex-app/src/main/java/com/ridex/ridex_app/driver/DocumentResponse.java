package com.ridex.ridex_app.driver;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        DocumentType type,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant uploadedAt
) {
    public static DocumentResponse from(DriverDocument document) {
        return new DocumentResponse(
                document.getId(), document.getType(), document.getOriginalFilename(),
                document.getContentType(), document.getSizeBytes(), document.getUploadedAt());
    }
}
