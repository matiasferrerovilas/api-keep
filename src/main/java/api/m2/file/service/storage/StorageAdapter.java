package api.m2.file.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.Set;

/**
 * Seam between {@link api.m2.file.service.FileService} and whatever actually stores the bytes.
 * Every method takes/returns plain {@link String} locations (not {@link java.nio.file.Path}) so an
 * implementation isn't forced into local-filesystem semantics — a future S3/NAS-backed adapter can
 * treat the string as an object key instead of a real path. {@link FileService} still does all path
 * *algebra* (joining segments, resolving parents, containment checks) with {@code Path} before
 * handing the resulting string down here; this interface only covers actual storage I/O.
 *
 * <p>{@code LocalFsStorageAdapter} is currently the only implementation, wrapping the exact
 * {@code java.nio.file.Files} calls FileService used to make directly — same exceptions, same
 * conditions. This is a pure refactor seam, not a new feature.
 */
public interface StorageAdapter {

    /** True if something (file or directory) exists at {@code location}. */
    boolean exists(String location);

    /** True if {@code location} exists and is a regular file. */
    boolean isRegularFile(String location);

    /** True if {@code location} exists and is a directory. */
    boolean isDirectory(String location);

    /** Creates the directory at {@code location}, including any missing parent directories.
     * No-op if it already exists. */
    void createDirectories(String location) throws IOException;

    /**
     * Stores the full content of {@code input} at {@code location}, creating any missing parent
     * directories first. Fails atomically with {@link FileAlreadyExistsException} if something is
     * already there — callers rely on this to make "does it exist" and "write it" a single
     * operation instead of racing a separate existence check against a concurrent writer.
     */
    void storeNew(String location, InputStream input) throws IOException;

    /** Streams the full content of the file at {@code location} to {@code output}. */
    void copyFileTo(String location, OutputStream output) throws IOException;

    /**
     * Streams a zip of the directory at {@code location} (recursively, entries relative to it,
     * directories included as their own entries) to {@code output}. Only paths present in
     * {@code includedRelativePaths} (forward-slash-separated, relative to {@code location}) are
     * added — this is what lets the caller (which knows the DB's {@code deletedAt} state; this
     * interface never sees the DB) exclude a file that's still physically on disk but has already
     * been sent to the trash, and hasn't been purged yet.
     */
    void zipDirectory(String location, Set<String> includedRelativePaths, OutputStream output) throws IOException;

    /** Best-effort MIME type sniff of the file at {@code location}; {@code null} if it can't be
     * determined. */
    String probeContentType(String location) throws IOException;

    /** Deletes whatever is at {@code location} if it exists. Returns whether it existed. A
     * directory must be empty to be deleted this way. */
    boolean deleteIfExists(String location) throws IOException;

    /** Moves/renames whatever is at {@code source} to {@code destination}. */
    void move(String source, String destination) throws IOException;
}
