package com.ticketflow.testsupport;

import java.time.Duration;

/**
 * The ONE place that defines how long a test may wait for something real (decision DP-035).
 *
 * <p>Rationale: the defaults of the test libraries are tight (WebTestClient answers after 5 s, Awaitility after
 * 10 s) and a loaded CI runner can exceed them without anything being wrong. Every deadline in an integration or
 * web test comes from here, never from a literal in the test. A deadline is only a safety net against a hung test:
 * it must be far above any legitimate duration, so it never changes what a test asserts.
 *
 * <p>Change the values here (and {@code spring.test.webtestclient.timeout} in
 * {@code src/test/resources/application.properties}, which {@code TestTimeoutsTest} keeps equal to
 * {@link #RESPONSE}).
 */
public final class TestTimeouts {

    /** Longest a test waits for one HTTP exchange (WebTestClient response timeout). */
    public static final Duration RESPONSE = Duration.ofSeconds(60);

    /**
     * Longest a test waits for anything else that is real: a blocked reactive call, an Awaitility condition, a
     * latch, a StepVerifier. Larger than {@link #RESPONSE} because it often spans several round trips.
     */
    public static final Duration WAIT = Duration.ofSeconds(90);

    /**
     * Longest a Testcontainers container may take to become ready (Testcontainers' own default is 60 s, which a cold
     * or loaded runner can exceed while LocalStack boots). Image pull time is not part of it.
     */
    public static final Duration CONTAINER_STARTUP = Duration.ofMinutes(3);

    private TestTimeouts() {}
}
