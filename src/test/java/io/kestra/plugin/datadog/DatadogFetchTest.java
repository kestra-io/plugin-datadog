package io.kestra.plugin.datadog;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatadogFetchTest extends AbstractDatadogTest {
    @Test
    void everyModeKeepsOnlyItsOutputAndTheFullCount() throws Exception {
        var rows = List.of(Map.<String, Object>of("id", 1), Map.<String, Object>of("id", 2));
        for (var mode : FetchType.values()) {
            var context = runContext();
            var result = DatadogFetch.apply(context, mode, rows);
            assertEquals(2, result.size());
            assertEquals(mode == FetchType.FETCH ? rows : null, result.rows());
            assertEquals(mode == FetchType.FETCH_ONE ? rows.getFirst() : null, result.row());
            if (mode == FetchType.STORE) {
                var stored = new ArrayList<Object>();
                try (var input = context.storage().getFile(result.uri())) {
                    FileSerde.read(input, stored::add);
                }
                assertEquals(List.of("1", "2"), stored.stream().map(row -> ((Map<?, ?>) row).get("id").toString()).toList());
            } else assertNull(result.uri());
        }
    }

    @Test
    void anEmptyFirstFetchHasNoRow() throws Exception {
        var result = DatadogFetch.apply(runContext(), FetchType.FETCH_ONE, List.of());
        assertEquals(0, result.size());
        assertNull(result.row());
        assertNull(result.rows());
        assertNull(result.uri());
    }
}
