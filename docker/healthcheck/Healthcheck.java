import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Container HEALTHCHECK probe: the distroless runtime image has no shell, curl or wget, so the probe is a
 * tiny Java program. It asks the LIVENESS probe of the actuator, served on the management port (never on the
 * public one): exit code 0 only if it answers 200 (status UP). Liveness is used on purpose: it does not depend
 * on DynamoDB or SQS, so an outage of a dependency never marks the container unhealthy (readiness is for load
 * balancers and dashboards, see docs/observability.md).
 */
public final class Healthcheck {

    private Healthcheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("MANAGEMENT_SERVER_PORT", "8081");
        try {
            HttpURLConnection connection = (HttpURLConnection) URI
                    .create("http://127.0.0.1:" + port + "/actuator/health/liveness").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            System.exit(connection.getResponseCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
