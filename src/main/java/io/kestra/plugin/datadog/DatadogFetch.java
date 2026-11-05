package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Returns the rows of a query inline, as one row, in internal storage or not at all, as the other Kestra query tasks do with {@code fetchType}. */
public final class DatadogFetch {
    private DatadogFetch() {
    }

    /** The rows kept for the output: only the field that matches the {@code fetchType} is set. {@code size} is always the number of rows found. */
    public record Result(List<Map<String, Object>> rows, Map<String, Object> row, URI uri, int size) {
    }

    public static Result apply(RunContext runContext, FetchType fetchType, List<Map<String, Object>> found) throws IOException {
        return switch (fetchType) {
            case FETCH -> new Result(found, null, null, found.size());
            case FETCH_ONE -> new Result(null, found.isEmpty() ? null : found.getFirst(), null, found.size());
            case NONE -> new Result(null, null, null, found.size());
            case STORE -> {
                var file = runContext.workingDir().createTempFile(".ion").toFile();
                try (OutputStream out = Files.newOutputStream(file.toPath())) {
                    for (Map<String, Object> row : found) {
                        FileSerde.write(out, row);
                    }
                }
                yield new Result(null, null, runContext.storage().putFile(file), found.size());
            }
        };
    }
}
