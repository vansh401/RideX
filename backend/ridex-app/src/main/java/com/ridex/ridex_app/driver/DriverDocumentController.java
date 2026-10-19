package com.ridex.ridex_app.driver;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/drivers/me/documents")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver documents", description = "Licence, registration, insurance and ID uploads of the logged-in driver")
public class DriverDocumentController {

    private final DriverDocumentService service;

    public DriverDocumentController(DriverDocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a document",
            description = "Multipart form with 'type' and 'file'. PDF, JPEG or PNG, up to 5 MB. "
                    + "Uploading the same type again replaces the earlier file.")
    public DocumentResponse upload(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @RequestParam DocumentType type,
                                   @RequestParam MultipartFile file) throws IOException {
        return service.upload(principal.id(), type, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping
    @Operation(summary = "List my documents (metadata only)")
    public List<DocumentResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal.id());
    }

    @GetMapping("/{documentId}/file")
    @Operation(summary = "Download one of my documents")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID documentId) {
        return asDownload(service.fileOfDriver(principal.id(), documentId));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of my documents", description = "Not possible once the profile is APPROVED.")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID documentId) {
        service.delete(principal.id(), documentId);
    }

    static ResponseEntity<byte[]> asDownload(DocumentFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                // "attachment": the browser saves the file instead of rendering it inside our origin
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
}
