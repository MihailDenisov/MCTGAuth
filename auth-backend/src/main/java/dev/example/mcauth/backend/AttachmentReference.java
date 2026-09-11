package dev.example.mcauth.backend;

public record AttachmentReference(
        String fileId,
        String fileUniqueId,
        String fileType,
        String caption) {
}
