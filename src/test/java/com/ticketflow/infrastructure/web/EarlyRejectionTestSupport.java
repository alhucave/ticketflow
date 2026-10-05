package com.ticketflow.infrastructure.web;

import com.ticketflow.infrastructure.web.EarlyRejectionProbe.Anomaly;
import com.ticketflow.infrastructure.web.EarlyRejectionProbe.Result;
import com.ticketflow.infrastructure.web.EarlyRejectionProbe.Scenario;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared setup of the early-rejection protocol tests (no Docker: the paths under test never reach DynamoDB or SQS). */
final class EarlyRejectionTestSupport {

    static final String ADMIN_KEY = "early-rejection-admin-key";
    private static final int CLOSED_PORT = closedPort();

    private EarlyRejectionTestSupport() {}

    /**
     * Iterations per scenario: small and fast by default; the large experiment of F-033 sets
     * {@code EARLY_REJECTION_ITERATIONS} (see docs/verification.md).
     */
    static int iterations() {
        return Integer.parseInt(System.getenv().getOrDefault("EARLY_REJECTION_ITERATIONS", "25"));
    }

    /** How long a reply may take before it counts as missing (the hang seen in CI was a reply that never came). */
    static Duration deadline() {
        return Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("EARLY_REJECTION_DEADLINE_MS", "5000")));
    }

    /** Dependencies point at a port nobody listens on: the probed paths are answered before any adapter is used. */
    static void unreachableDependencies(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.dynamodb.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("ticketflow.dynamodb.access-key-id", () -> "test");
        registry.add("ticketflow.dynamodb.secret-access-key", () -> "test");
        registry.add("ticketflow.sqs.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("ticketflow.sqs.access-key-id", () -> "test");
        registry.add("ticketflow.sqs.secret-access-key", () -> "test");
        registry.add("ticketflow.admin.api-key", () -> ADMIN_KEY);
    }

    /** Runs every scenario in both modes (waiting for the reply, and pipelined) and returns all anomalies. */
    static List<Anomaly> runAll(EarlyRejectionProbe probe, List<Scenario> scenarios) {
        List<Anomaly> anomalies = new java.util.ArrayList<>();
        for (Scenario scenario : scenarios) {
            for (boolean pipelined : new boolean[] {false, true}) {
                if (pipelined && !scenario.pipelineable()) {
                    continue;
                }
                Result result = probe.run(scenario, iterations(), pipelined);
                System.out.println("[early-rejection] " + result);
                anomalies.addAll(result.anomalies());
            }
        }
        return anomalies;
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
