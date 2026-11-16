package dev.arthurgaspar.expeditions.processing;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Calls dungeon-sim over HTTP. The read timeout is the time limit on a call: past it the call counts as failed
 * (and as slow, for the circuit breaker) even if the simulator answers later.
 */
public class HttpDungeonSim implements DungeonSim {

    private final RestClient http;

    public HttpDungeonSim(RestClient.Builder builder, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(readTimeout);
        this.http = builder.baseUrl(baseUrl).requestFactory(requests).build();
    }

    @Override
    public Result run(String runId, long expeditionId, long partyId, List<String> members) {
        return http.post().uri("/api/runs")
                .header("Idempotency-Key", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("expeditionId", expeditionId, "partyId", partyId, "members", members))
                .retrieve()
                .body(Result.class);
    }
}
