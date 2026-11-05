package io.kestra.plugin.datadog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SourceConventionsTest {
    private static final Pattern NON_CODE = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|/\\*[\\s\\S]*?\\*/|//[^\\n]*");

    @Test
    void descriptionsUsePlainPunctuation() throws Exception {
        var forbidden = Pattern.compile("[\\u2013\\u2014]|, (?:and|or)\\b");
        for (var root : Set.of("src/main/java/io/kestra/plugin/datadog", "docs/areas")) {
            try (var paths = Files.walk(Path.of(root))) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(forbidden.matcher(Files.readString(path)).find(), path.toString());
                }
            }
        }
    }

    @Test
    void everyAreaUsesTheOfficialPluginIcon() throws Exception {
        var icons = Path.of("src/main/resources/icons");
        var reference = Files.readAllBytes(icons.resolve("plugin-icon.svg"));
        try (var paths = Files.list(icons)) {
            var areas = paths.filter(path -> path.getFileName().toString().matches("io\\.kestra\\.plugin\\.datadog\\.[^.]+\\.svg")).toList();
            assertFalse(areas.isEmpty());
            for (var area : areas) assertArrayEquals(reference, Files.readAllBytes(area), area.toString());
        }
    }

    @Test
    void taskBuildersUseRandomIds() throws Exception {
        try (var paths = Files.walk(Path.of("src/test/java/io/kestra/plugin/datadog"))) {
            for (var path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                var code = NON_CODE.matcher(Files.readString(path)).replaceAll(" ");
                var ids = Pattern.compile("\\.id\\((?!\\s*\\))").matcher(code);
                while (ids.find()) assertTrue(code.substring(ids.end()).startsWith("IdUtils.create()"), path + " uses a nonrandom builder id");
            }
        }
    }

    @Test
    void renderedLocalsAndClientOwnershipFollowTheReviewedPattern() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java/io/kestra/plugin/datadog"))) {
            for (var path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                var code = NON_CODE.matcher(Files.readString(path)).replaceAll(" ");
                assertFalse(Pattern.compile("\\brendered[A-Z]\\w*").matcher(code).find(), path.toString());
                var rendered = Pattern.compile("\\b(?:var|String|Integer|Long|Duration|Instant|boolean|int|long|double) (\\w+) = (?:context|runContext)\\.render\\(").matcher(code);
                while (rendered.find()) assertTrue(rendered.group(1).matches("r[A-Z].*"), path + ": " + rendered.group(1));
                if (Set.of("DatadogClient.java", "AbstractDatadogTask.java").contains(path.getFileName().toString())) continue;
                for (var line : code.split("\\n")) {
                    if (line.matches(".*= (?:client\\(|DatadogClient\\.create\\().*")) assertTrue(line.stripLeading().startsWith("try ("), path + ": " + line);
                }
                if (!path.getFileName().toString().equals("DatadogFetch.java")) assertFalse(code.contains("FileSerde.write("), path + " duplicates ION output");
            }
        }
    }
}
