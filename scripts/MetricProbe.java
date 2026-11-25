import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MetricProbe {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("RAG_METRIC_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("RAG_METRIC_TOKEN is required");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://metric-service:8090/internal/v1/health"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().equals("{\"service\":\"metric-service\",\"status\":\"UP\"}")) {
                throw new IllegalStateException("metric-service did not pass the probe (HTTP " + response.statusCode() + ")");
            }
            System.out.println("Java -> metric-service:8090 returned HTTP 200");
        }
    }
}
