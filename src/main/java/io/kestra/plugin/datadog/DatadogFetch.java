package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Returns the rows of a query inline, as one row, in internal storage or not at all, as the other Kestra query tasks do with {@code fetchType}. */
public final class DatadogFetch {
    private DatadogFetch() {
    }

    /** The rows kept for the output: only the field that matches the {@code fetchType} is set. {@code size} is always the number of rows found. */
    public record Result<T>(List<T> rows, T row, URI uri, int size) {
    }

    public static <T> Result<T> apply(RunContext runContext, FetchType fetchType, List<T> found) throws IOException {
        try (var sink = new Sink<T>(runContext, fetchType)) {
            for (var row : found) {
                sink.add(row);
            }
            return sink.result();
        }
    }

    /** Keeps configuration exports readable by the matching import task. */
    public static URI storeDocument(RunContext context, Object value, boolean yaml) throws IOException {
        var path = context.workingDir().createTempFile(yaml ? ".yaml" : ".json");
        try {
            (yaml ? JacksonMapper.ofYaml() : JacksonMapper.ofJson()).writeValue(path.toFile(), value);
            return context.storage().putFile(path.toFile());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /** Keeps only the requested output and publishes stored rows after successful paging. */
    public static final class Sink<T> implements AutoCloseable {
        private final RunContext context;
        private final FetchType fetchType;
        private final List<T> rows;
        private final Path path;
        private final OutputStream writer;
        private T row;
        private int size;
        private URI uri;

        public Sink(RunContext context, FetchType fetchType) throws IOException {
            this.context = context;
            this.fetchType = fetchType;
            rows = fetchType == FetchType.FETCH ? new ArrayList<>() : null;
            path = fetchType == FetchType.STORE ? context.workingDir().createTempFile(".ion") : null;
            writer = path == null ? null : new BufferedOutputStream(Files.newOutputStream(path));
        }

        public void add(T value) throws IOException {
            switch (fetchType) {
                case FETCH -> rows.add(value);
                case FETCH_ONE -> {
                    if (size == 0) row = value;
                }
                case STORE -> FileSerde.write(writer, value);
                case NONE -> { }
            }
            size++;
        }

        public int size() {
            return size;
        }

        public Result<T> result() throws IOException {
            if (writer != null && uri == null) {
                writer.flush();
                uri = context.storage().putFile(path.toFile());
            }
            return new Result<>(rows, row, uri, size);
        }

        @Override
        public void close() throws IOException {
            if (writer != null) {
                try {
                    writer.close();
                } finally {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
