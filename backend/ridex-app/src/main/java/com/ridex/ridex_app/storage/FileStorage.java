package com.ridex.ridex_app.storage;

/**
 * Where uploaded files live. The database keeps only metadata and the returned key.
 * Today the implementation writes to disk; production would swap in object storage (S3-style)
 * without touching the callers.
 */
public interface FileStorage {

    /** Stores the bytes and returns an opaque key chosen by us (never derived from user input). */
    String store(String folder, String extension, byte[] content);

    byte[] read(String key);

    /** Removes the file; a missing file is not an error. */
    void delete(String key);
}
