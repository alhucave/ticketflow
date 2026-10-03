import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Container HEALTHCHECK probe: the distroless runtime image has no shell, curl or wget, so the probe is a
 * tiny Java program. Exit code 0 only if the actuator health endpoint answers 200 (status UP).
 */
public final class Healthcheck {

    private Healthcheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("SERVER_PORT", "8080");
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/actuator/health").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            System.exit(connection.getResponseCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
