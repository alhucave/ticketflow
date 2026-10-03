package com.ticketflow.infrastructure.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test helper: captures what the application logs in Spring Boot's real structured (ECS) format, whatever the
 * console format of the JVM happens to be (logging is global state shared by the cached test contexts). The
 * encoder is the same one {@code logging.structured.format.console=ecs} installs, configured from the real
 * {@link Environment} of the application under test (service name, version, environment).
 */
public final class JsonLogCapture implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new OutputStreamAppender<>();

    public JsonLogCapture(Environment environment, String format) {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(format);
        encoder.start();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();
        root.addAppender(appender);
    }

    public JsonLogCapture(Environment environment) {
        this(environment, "ecs");
    }

    /** Everything captured so far, as written. */
    public synchronized String text() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Each captured line parsed as JSON; a line that is not a JSON object fails the parse (and the test). */
    public List<JsonNode> lines() {
        return text().lines().filter(line -> !line.isBlank()).map(JSON::readTree).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
