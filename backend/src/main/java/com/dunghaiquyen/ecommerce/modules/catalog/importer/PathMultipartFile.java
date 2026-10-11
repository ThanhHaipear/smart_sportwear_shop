package com.dunghaiquyen.ecommerce.modules.catalog.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

final class PathMultipartFile implements MultipartFile {
    private final Path path;
    private final byte[] bytes;

    PathMultipartFile(Path path) throws IOException {
        this.path = path;
        this.bytes = Files.readAllBytes(path);
    }

    @Override public String getName() { return "file"; }
    @Override public String getOriginalFilename() { return path.getFileName().toString(); }
    @Override public String getContentType() {
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".webp")) return "image/webp";
        return "image/png";
    }
    @Override public boolean isEmpty() { return bytes.length == 0; }
    @Override public long getSize() { return bytes.length; }
    @Override public byte[] getBytes() { return bytes.clone(); }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    @Override public void transferTo(java.io.File destination) throws IOException { Files.write(destination.toPath(), bytes); }
}
