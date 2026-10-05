package com.picsou.imports.actual;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded Actual Budget export into {@link ParsedActualBudget}. Accepts the budget
 * {@code .zip} export (which holds {@code db.sqlite} and {@code metadata.json}) or a bare
 * {@code db.sqlite}, recognised by their signatures rather than by file name.
 *
 * <p>The upload is user-supplied, so the archive is read defensively: entry count and total
 * inflated size are capped (counted while inflating, never trusted from the headers), any entry
 * whose name could escape a directory rejects the whole archive, and only the root
 * {@code db.sqlite} is kept. Nothing is ever written under an entry's own name. The database is
 * written to a private temporary directory, opened read-only, and the directory is deleted
 * whatever the outcome.
 */
@Component
public class ActualBudgetFileParser {

    static final String DATABASE_ENTRY = "db.sqlite";
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final int DEFAULT_MAX_ENTRIES = 32;
    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024;

    private final int maxEntries;
    private final long maxUncompressedBytes;
    private final ActualBudgetDatabaseReader reader;

    public ActualBudgetFileParser() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_UNCOMPRESSED_BYTES, new ActualBudgetDatabaseReader());
    }

    ActualBudgetFileParser(int maxEntries, long maxUncompressedBytes, ActualBudgetDatabaseReader reader) {
        this.maxEntries = maxEntries;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.reader = reader;
    }

    public ParsedActualBudget parse(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw bad("The file is empty");
        }
        Path directory = null;
        try {
            directory = createPrivateDirectory();
            Path database = directory.resolve("budget.sqlite");
            writeDatabase(upload, database);
            return reader.read(database);
        } catch (ZipException e) {
            throw bad("The Actual Budget archive is corrupt");
        } catch (IOException e) {
            throw bad("Unable to read the Actual Budget file");
        } finally {
            deleteRecursively(directory);
        }
    }

    private void writeDatabase(byte[] upload, Path database) throws IOException {
        if (startsWith(upload, SQLITE_MAGIC)) {
            if (upload.length > maxUncompressedBytes) {
                throw bad("The Actual Budget database is too large");
            }
            Files.write(database, upload);
            return;
        }
        if (!startsWith(upload, ZIP_MAGIC)) {
            throw bad("Unsupported file: expected an Actual Budget export (.zip) or its db.sqlite");
        }
        extractFromZip(upload, database);
        byte[] header = new byte[SQLITE_MAGIC.length];
        try (InputStream in = Files.newInputStream(database)) {
            if (in.readNBytes(header, 0, header.length) != header.length || !Arrays.equals(header, SQLITE_MAGIC)) {
                throw bad("The archive's db.sqlite is not a SQLite database");
            }
        }
    }

    private void extractFromZip(byte[] upload, Path database) throws IOException {
        int entries = 0;
        long inflated = 0;
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(upload))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw bad("The Actual Budget archive has too many entries");
                }
                String name = entry.getName();
                if (isUnsafe(name)) {
                    throw bad("The Actual Budget archive contains an unsafe path");
                }
                if (DATABASE_ENTRY.equals(name) && !entry.isDirectory()) {
                    if (found) {
                        throw bad("The Actual Budget archive contains several db.sqlite files");
                    }
                    found = true;
                    try (OutputStream out = Files.newOutputStream(database)) {
                        inflated = copyBounded(zip, out, inflated);
                    }
                } else {
                    inflated = copyBounded(zip, OutputStream.nullOutputStream(), inflated);
                }
            }
        }
        if (entries == 0) {
            throw bad("The Actual Budget archive is corrupt");
        }
        if (!found) {
            throw bad("The archive does not contain an Actual Budget db.sqlite");
        }
    }

    /** Copies one entry while counting every inflated byte of the archive against the cap. */
    private long copyBounded(InputStream in, OutputStream out, long alreadyInflated) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = alreadyInflated;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxUncompressedBytes) {
                throw bad("The Actual Budget archive is too large once uncompressed");
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private static boolean isUnsafe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.indexOf('\0') >= 0) {
            return true;
        }
        return Arrays.asList(name.split("/")).contains("..");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length
                && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static Path createPrivateDirectory() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("picsou-actual-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory("picsou-actual-");
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }

    static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
