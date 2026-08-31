package api.m2.file.service.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Wraps {@code java.nio.file.Files} — the local disk. This is verbatim the logic FileService used
 * to inline directly; behavior (including which exception is thrown under which condition) is
 * unchanged, it just now lives behind {@link StorageAdapter} so a future backend (S3, NAS, ...)
 * can be swapped in without touching FileService.
 */
@Component
public class LocalFsStorageAdapter implements StorageAdapter {

    @Override
    public boolean exists(String location) {
        return Files.exists(Path.of(location));
    }

    @Override
    public boolean isRegularFile(String location) {
        return Files.isRegularFile(Path.of(location));
    }

    @Override
    public boolean isDirectory(String location) {
        return Files.isDirectory(Path.of(location));
    }

    @Override
    public void createDirectories(String location) throws IOException {
        Files.createDirectories(Path.of(location));
    }

    @Override
    public void storeNew(String location, InputStream input) throws IOException {
        Path target = Path.of(location);
        Files.createDirectories(target.getParent());
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            input.transferTo(output);
        }
    }

    @Override
    public void copyFileTo(String location, OutputStream output) throws IOException {
        Files.copy(Path.of(location), output);
    }

    @Override
    public void zipDirectory(String location, Set<String> includedRelativePaths, OutputStream output) throws IOException {
        Path sourceDir = Path.of(location);
        try (ZipOutputStream zos = new ZipOutputStream(output);
             var stream = Files.walk(sourceDir)) {
            for (Path path : stream.filter(p -> !p.equals(sourceDir)).sorted().toList()) {
                String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
                if (!includedRelativePaths.contains(entryName)) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    zos.putNextEntry(new ZipEntry(entryName + "/"));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    @Override
    public String probeContentType(String location) throws IOException {
        return Files.probeContentType(Path.of(location));
    }

    @Override
    public boolean deleteIfExists(String location) throws IOException {
        return Files.deleteIfExists(Path.of(location));
    }

    @Override
    public void move(String source, String destination) throws IOException {
        Files.move(Path.of(source), Path.of(destination));
    }
}
