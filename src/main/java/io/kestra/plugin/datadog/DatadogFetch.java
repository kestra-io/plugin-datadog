package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

/** Returns the rows of a query inline, as one row, in internal storage or not at all, as the other Kestra query tasks do with {@code fetchType}. */
public final class DatadogFetch {
    private DatadogFetch() {
    }

    /** The rows kept for the output: only the field that matches the {@code fetchType} is set. {@code size} is always the number of rows found. */
    public record Result<T>(List<T> rows, T row, URI uri, int size) {
    }

    public static <T> Result<T> apply(RunContext runContext, FetchType fetchType, List<T> found) throws IOException {
        return switch (fetchType) {
            case FETCH -> new Result<>(found, null, null, found.size());
            case FETCH_ONE -> new Result<>(null, found.isEmpty() ? null : found.getFirst(), null, found.size());
            case NONE -> new Result<>(null, null, null, found.size());
            case STORE -> {
                var path = runContext.workingDir().createTempFile(".ion");
                try {
                    try (var writer = new BufferedOutputStream(Files.newOutputStream(path))) {
                        for (var row : found) {
                            FileSerde.write(writer, row);
                        }
                    }
                    yield new Result<>(null, null, runContext.storage().putFile(path.toFile()), found.size());
                } finally {
                    Files.deleteIfExists(path);
                }
            }
        };
    }
}
