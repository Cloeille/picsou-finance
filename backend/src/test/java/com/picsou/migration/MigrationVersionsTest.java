package com.picsou.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two branches that each add the next migration produce two files with the same version and
 * different names, which git merges without a conflict and Flyway refuses at startup, taking
 * every Spring context test down with it. This fails on the merge instead, naming both files.
 */
class MigrationVersionsTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void everyVersionedMigrationHasAUniqueVersion() throws IOException {
        Map<Integer, List<String>> byVersion = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            files.map(p -> p.getFileName().toString()).forEach(name -> {
                Matcher m = VERSIONED.matcher(name);
                if (m.matches()) {
                    byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new java.util.ArrayList<>()).add(name);
                }
            });
        }

        assertThat(byVersion).isNotEmpty();
        assertThat(byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1).toList())
            .as("migration versions used by more than one file")
            .isEmpty();
    }
}
