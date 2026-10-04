package com.ticketflow.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the test suite on the shared timeout policy (DP-035): a source scan of {@code src/test/java} that fails when
 * a test builds a WebTestClient without the shared timeout, sets its own response timeout, hard-codes a deadline in
 * {@code block}/{@code verify}/{@code atMost}, or starts a container without the shared startup timeout. The
 * {@code testsupport} package itself is exempt (it defines the policy and proves it).
 */
class TimeoutPolicyGuardTest {

    private static final Path TEST_SOURCES = Path.of("src/test/java");
    private static final Pattern BIND = Pattern.compile("WebTestClient\\s*\\.\\s*bindTo");
    private static final Pattern LITERAL_DEADLINE = Pattern.compile(
            "(\\.block|\\.verify|atMost|withStartupTimeout)\\(\\s*Duration\\.of"
                    + "|Duration\\s+(WAIT|TIMEOUT)\\s*=\\s*Duration\\.of");

    @Test
    void everyTestSourceFollowsTheSharedTimeoutPolicy() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.toString().contains("/testsupport/")) {
                    continue;
                }
                violations.addAll(violations(file.toString(), Files.readString(file)));
            }
        }
        assertThat(violations).as("tests that bypass the shared timeout policy (see TestTimeouts)").isEmpty();
    }

    @Test
    void scanner_flagsEachKindOfViolation_soTheGuardCannotPassVacuously() {
        assertThat(violations("A.java", "@AutoConfigureWebTestClient(timeout = \"30s\") class A {}")).hasSize(1);
        assertThat(violations("B.java", "var c = WebTestClient.bindToController(x).build();")).hasSize(1);
        assertThat(violations("C.java", "builder.responseTimeout(Duration.ofSeconds(9));")).hasSize(1);
        assertThat(violations("D.java", "mono.block(Duration.ofSeconds(5));")).hasSize(1);
        assertThat(violations("E.java", "await().atMost(Duration.ofSeconds(5)).until(x);")).hasSize(1);
        assertThat(violations("F.java", "private static final Duration WAIT = Duration.ofSeconds(60);")).hasSize(1);
        assertThat(violations("G.java", "new GenericContainer<>(image).withExposedPorts(1);")).hasSize(1);
        assertThat(violations("H.java", "TestWebClients.build(WebTestClient.bindToController(x)); mono.block(TestTimeouts.WAIT);"
                + " @AutoConfigureWebTestClient class H {}")).isEmpty();
    }

    static List<String> violations(String name, String source) {
        List<String> found = new ArrayList<>();
        if (source.contains("@AutoConfigureWebTestClient(")) {
            found.add(name + ": @AutoConfigureWebTestClient(...) sets its own timeout; the property in "
                    + "src/test/resources/application.properties applies to every injected client");
        }
        if (source.contains(".responseTimeout(")) {
            found.add(name + ": sets .responseTimeout(...) by hand; use TestWebClients / TestTimeouts.RESPONSE");
        }
        if (count(BIND, source) > count(Pattern.compile("TestWebClients\\s*\\.\\s*build\\("), source)) {
            found.add(name + ": builds a WebTestClient with WebTestClient.bindTo... without TestWebClients.build(...)");
        }
        if (LITERAL_DEADLINE.matcher(source).find()) {
            found.add(name + ": hard-coded deadline; use TestTimeouts.WAIT / TestTimeouts.CONTAINER_STARTUP");
        }
        long containers = count(Pattern.compile("new\\s+(GenericContainer|LocalStackContainer)\\b"), source);
        if (containers > count(Pattern.compile("withStartupTimeout\\(TestTimeouts\\.CONTAINER_STARTUP\\)"), source)) {
            found.add(name + ": starts a container without .withStartupTimeout(TestTimeouts.CONTAINER_STARTUP)");
        }
        return found;
    }

    private static long count(Pattern pattern, String source) {
        return pattern.matcher(source).results().count();
    }
}
