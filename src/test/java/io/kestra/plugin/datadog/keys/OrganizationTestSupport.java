package io.kestra.plugin.datadog.keys;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class OrganizationTestSupport extends AbstractDatadogTest {
    protected static final String VALUE = "fixture-credential-do-not-log-1234";
    private Object logs;
    private Object root;
    private final List<Object> contextLoggers = new ArrayList<>();

    @Override
    protected RunContext runContext() {
        var context = super.runContext();
        try {
            var logger = context.logger();
            logger.getClass().getMethod("addAppender", Class.forName("ch.qos.logback.core.Appender")).invoke(logger, logs);
            contextLoggers.add(logger);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not capture task logs", e);
        }
        return context;
    }

    @BeforeEach
    void captureLogs() throws Exception {
        // Logback is provided by Kestra at runtime rather than on the compile classpath.
        logs = Class.forName("ch.qos.logback.core.read.ListAppender").getConstructor().newInstance();
        logs.getClass().getMethod("start").invoke(logs);
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.getClass().getMethod("addAppender", Class.forName("ch.qos.logback.core.Appender")).invoke(root, logs);
        LoggerFactory.getLogger(getClass()).warn("Organization test log capture active");
    }

    @AfterEach
    void checkLogs() throws Exception {
        root.getClass().getMethod("detachAppender", Class.forName("ch.qos.logback.core.Appender")).invoke(root, logs);
        for (var logger : contextLoggers) {
            logger.getClass().getMethod("detachAppender", Class.forName("ch.qos.logback.core.Appender")).invoke(logger, logs);
        }
        contextLoggers.clear();
        logs.getClass().getMethod("stop").invoke(logs);
        List<?> events = (List<?>) logs.getClass().getField("list").get(logs);
        var marker = false;
        for (var event : events) {
            var message = (String) event.getClass().getMethod("getFormattedMessage").invoke(event);
            marker |= message.contains("log capture active");
            assertFalse(message.contains(VALUE), "Response credential reached a log line");
            assertFalse(message.contains(API_KEY), "API credential reached a log line");
            assertFalse(message.contains(APP_KEY), "Application credential reached a log line");
            var throwable = event.getClass().getMethod("getThrowableProxy").invoke(event);
            if (throwable != null) {
                assertFalse(String.valueOf(throwable.getClass().getMethod("getMessage").invoke(throwable)).contains(VALUE));
            }
        }
        assertTrue(marker);
    }

    protected <B extends AbstractDatadogTask.AbstractDatadogTaskBuilder<?, ?>> B connection(B builder) {
        builder.id(IdUtils.create()).type("io.kestra.plugin.datadog.keys.Create")
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()));
        return builder;
    }

    protected static String json(Object value) throws Exception {
        return JacksonMapper.ofJson().writeValueAsString(value);
    }

    protected static Map<String, Object> resource(String id, String type, Map<String, Object> attributes) {
        return Map.of("id", id, "type", type, "attributes", attributes);
    }

    protected static String page(List<?> rows, int total) throws Exception {
        return json(Map.of("data", rows, "meta", Map.of("page", Map.of("total_filtered_count", total))));
    }

    protected void noCredential(Object output) throws Exception {
        assertFalse(json(output).contains(VALUE));
    }
}
