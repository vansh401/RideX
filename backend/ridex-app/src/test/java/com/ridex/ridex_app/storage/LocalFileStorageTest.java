package com.ridex.ridex_app.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path directory;

    @Test
    void storesReadsAndDeletes() {
        LocalFileStorage storage = new LocalFileStorage(directory.toString());

        String key = storage.store("docs/abc", "pdf", new byte[] {1, 2, 3});

        assertThat(key).startsWith("docs/abc/").endsWith(".pdf");
        assertThat(storage.read(key)).containsExactly(1, 2, 3);

        storage.delete(key);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void deletingAMissingFileIsNotAnError() {
        new LocalFileStorage(directory.toString()).delete("docs/nothing-here.pdf");
    }

    @Test
    void twoUploadsNeverShareAFile() {
        LocalFileStorage storage = new LocalFileStorage(directory.toString());

        assertThat(storage.store("docs", "png", new byte[] {1})).isNotEqualTo(storage.store("docs", "png", new byte[] {1}));
    }

    @Test
    void aKeyCannotEscapeTheStorageFolder() {
        LocalFileStorage storage = new LocalFileStorage(directory.toString());

        assertThatThrownBy(() -> storage.read("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("docs/../../outside.txt")).isInstanceOf(IllegalArgumentException.class);
    }
}
