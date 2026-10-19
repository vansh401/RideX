package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.common.ConflictException;
import com.ridex.ridex_app.common.ResourceNotFoundException;
import com.ridex.ridex_app.common.UnsupportedFileException;
import com.ridex.ridex_app.storage.FileStorage;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;
import com.ridex.ridex_app.user.UserService;

class DriverDocumentServiceTest {

    /** A tiny in-memory storage so the tests can see exactly what was written and deleted. */
    static class MemoryStorage implements FileStorage {
        final Map<String, byte[]> files = new HashMap<>();
        int counter;

        @Override
        public String store(String folder, String extension, byte[] content) {
            String key = folder + "/file-" + (++counter) + "." + extension;
            files.put(key, content);
            return key;
        }

        @Override
        public byte[] read(String key) {
            return files.get(key);
        }

        @Override
        public void delete(String key) {
            files.remove(key);
        }
    }

    private final DriverProfileRepository profileRepository = mock(DriverProfileRepository.class);
    private final DriverDocumentRepository documentRepository = mock(DriverDocumentRepository.class);
    private final MemoryStorage storage = new MemoryStorage();
    private final UserService userService = mock(UserService.class);
    private final DriverDocumentService service =
            new DriverDocumentService(profileRepository, documentRepository, storage, userService);

    private final User user = new User("Ravi Kumar", "ravi@example.com", "+919812345678", "hash", Role.DRIVER);
    private final DriverProfile profile = new DriverProfile(user, "DL-12345", LocalDate.now().plusYears(2));

    private static byte[] pdf() {
        return "%PDF-1.7 hello".getBytes(StandardCharsets.US_ASCII);
    }

    private void profileExists() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));
        when(documentRepository.save(any(DriverDocument.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void storesAPdfUnderANameWeChoose() {
        profileExists();

        DocumentResponse response = service.upload(user.getId(), DocumentType.DRIVING_LICENSE, "../../my licence.pdf", pdf());

        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.originalFilename()).isEqualTo("my licence.pdf");   // path stripped, display only
        assertThat(storage.files).hasSize(1);
        assertThat(storage.files.keySet().iterator().next()).doesNotContain("licence").endsWith(".pdf");
    }

    @Test
    void trustsTheBytesNotTheFileNameOrDeclaredType() {
        profileExists();

        assertThatThrownBy(() -> service.upload(user.getId(), DocumentType.ID_PROOF, "totally-a.pdf",
                "MZ-this-is-an-executable".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(UnsupportedFileException.class)
                .hasMessageContaining("PDF, JPEG and PNG");
        assertThat(storage.files).isEmpty();
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        profileExists();

        assertThatThrownBy(() -> service.upload(user.getId(), DocumentType.INSURANCE, "a.pdf", new byte[0]))
                .isInstanceOf(UnsupportedFileException.class);
        byte[] big = new byte[(int) DriverDocumentService.MAX_BYTES + 1];
        System.arraycopy(pdf(), 0, big, 0, pdf().length);
        assertThatThrownBy(() -> service.upload(user.getId(), DocumentType.INSURANCE, "a.pdf", big))
                .isInstanceOf(UnsupportedFileException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    void uploadingTheSameTypeAgainReplacesTheOldFile() {
        profileExists();
        String oldKey = storage.store("driver-documents/x", "pdf", pdf());
        DriverDocument old = new DriverDocument(profile, DocumentType.DRIVING_LICENSE, "old.pdf", "application/pdf", 5, oldKey);
        when(documentRepository.findByDriverProfileIdAndType(profile.getId(), DocumentType.DRIVING_LICENSE))
                .thenReturn(Optional.of(old));

        service.upload(user.getId(), DocumentType.DRIVING_LICENSE, "new.pdf", pdf());

        verify(documentRepository).delete(old);
        assertThat(storage.files).doesNotContainKey(oldKey).hasSize(1);
    }

    @Test
    void documentsAreLockedOnceApproved() {
        profileExists();
        profile.approve(UUID.randomUUID(), Instant.now());

        assertThatThrownBy(() -> service.upload(user.getId(), DocumentType.INSURANCE, "a.pdf", pdf()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.delete(user.getId(), UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void needsAProfileFirst() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload(user.getId(), DocumentType.INSURANCE, "a.pdf", pdf()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDocumentIdFromAnotherProfileIsNotFound() {
        profileExists();
        when(documentRepository.findByIdAndDriverProfileId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fileOfDriver(user.getId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void filenamesAreCleanedForDisplay() {
        assertThat(DriverDocumentService.cleanFilename("C:\\Users\\me\\scan.pdf")).isEqualTo("scan.pdf");
        assertThat(DriverDocumentService.cleanFilename("/etc/passwd")).isEqualTo("passwd");
        assertThat(DriverDocumentService.cleanFilename("  ")).isEqualTo("document");
        assertThat(DriverDocumentService.cleanFilename(null)).isEqualTo("document");
        assertThat(DriverDocumentService.cleanFilename("a".repeat(400))).hasSize(255);
    }
}
