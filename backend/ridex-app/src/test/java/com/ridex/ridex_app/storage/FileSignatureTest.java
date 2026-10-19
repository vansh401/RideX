package com.ridex.ridex_app.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileSignatureTest {

    @Test
    void recognisesPdf() {
        assertThat(FileSignature.detect("%PDF-1.7 rest".getBytes(StandardCharsets.US_ASCII)))
                .hasValueSatisfying(d -> assertThat(d.contentType()).isEqualTo("application/pdf"));
    }

    @Test
    void recognisesJpegAndPng() {
        assertThat(FileSignature.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                .hasValueSatisfying(d -> assertThat(d.extension()).isEqualTo("jpg"));
        assertThat(FileSignature.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}))
                .hasValueSatisfying(d -> assertThat(d.extension()).isEqualTo("png"));
    }

    @Test
    void rejectsAnythingElseWhateverItIsCalled() {
        // A Windows executable renamed to resume.pdf is still an executable
        assertThat(FileSignature.detect("MZ-program".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(FileSignature.detect("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileSignature.detect(new byte[0])).isEmpty();
        assertThat(FileSignature.detect(new byte[] {(byte) 0xFF})).isEmpty();
    }
}
