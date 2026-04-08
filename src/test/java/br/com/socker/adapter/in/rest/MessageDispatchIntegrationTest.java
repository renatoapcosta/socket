package br.com.socker.adapter.in.rest;

import br.com.socker.application.port.out.ConcentratorGateway;
import br.com.socker.application.usecase.DispatchMessageUseCaseImpl;
import br.com.socker.domain.exception.ConnectionQueueFullException;
import br.com.socker.domain.exception.NoActiveConnectionException;
import br.com.socker.domain.model.IsoMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: real Javalin server on a random port + hand-written stub
 * {@link ConcentratorGateway}.
 *
 * <p>Exercises the full adapter stack (HTTP → DTO → use case → port) without
 * requiring a live TCP socket to the Concentrador.
 *
 * <p>Manual stub is used instead of Mockito to avoid bytecode-manipulation
 * limitations on Java 25 with {@code --enable-preview}.
 */
class MessageDispatchIntegrationTest {

    // ─── Stub ─────────────────────────────────────────────────────────────────

    static class StubGateway implements ConcentratorGateway {
        IsoMessage lastMessage;
        RuntimeException toThrow;

        @Override
        public void send(IsoMessage message) {
            if (toThrow != null) throw toThrow;
            this.lastMessage = message;
        }

        boolean received() { return lastMessage != null; }
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private StubGateway gateway;
    private RestServerAdapter server;
    private HttpClient http;
    private String baseUrl;

    @BeforeEach
    void startServer() {
        gateway = new StubGateway();
        DispatchMessageUseCaseImpl useCase = new DispatchMessageUseCaseImpl(gateway);
        MessageController controller       = new MessageController(useCase);
        server   = new RestServerAdapter(0, controller); // 0 = OS-assigned port
        http     = HttpClient.newHttpClient();
        baseUrl  = "http://localhost:" + server.getPort();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    // ─── 202 Accepted ─────────────────────────────────────────────────────────

    @Test
    void post_valid0600_returns202() throws Exception {
        assertThat(post("/comunicacao/sessions/s1/messages", validProbeJson()).statusCode())
            .isEqualTo(202);
    }

    @Test
    void post_valid0600_noResponseBody() throws Exception {
        HttpResponse<String> response = post("/comunicacao/sessions/s1/messages", validProbeJson());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isNullOrEmpty();
    }

    @Test
    void post_decodedIsoMessage_hasCorrectMti() throws Exception {
        post("/comunicacao/sessions/s1/messages", validProbeJson());

        assertThat(gateway.lastMessage).isNotNull();
        assertThat(gateway.lastMessage.getMessageType().getMti()).isEqualTo("0600");
    }

    @Test
    void post_decodedIsoMessage_hasAllBits() throws Exception {
        post("/comunicacao/sessions/s1/messages", validProbeJson());

        IsoMessage msg = gateway.lastMessage;
        assertThat(msg.getRequiredField(3)).isEqualTo("100000");
        assertThat(msg.getRequiredField(7)).isEqualTo("0327162336");
        assertThat(msg.getRequiredField(11)).isEqualTo("132256");
        assertThat(msg.getRequiredField(42)).isEqualTo("644400000000001");
        assertThat(msg.getRequiredField(125)).isEqualTo("000132256");
        assertThat(msg.getRequiredField(127)).isEqualTo("000248756");
    }

    @Test
    void post_withOptionalBitsAbsent_returns202() throws Exception {
        String json = """
            {"mti":"0600","bit_003":"100000","bit_007":"0327162336",
             "bit_011":"132256","bit_012":"162338","bit_013":"0327",
             "bit_042":"644400000000001","bit_125":"000132256","bit_127":"000248756"}
            """;

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(202);
        assertThat(gateway.lastMessage.hasField(32)).isFalse();
        assertThat(gateway.lastMessage.hasField(41)).isFalse();
    }

    @Test
    void post_withRequestIdHeader_returns202() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/comunicacao/sessions/s1/messages"))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", "req-abc-123")
            .POST(HttpRequest.BodyPublishers.ofString(validProbeJson()))
            .build();

        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
            .isEqualTo(202);
    }

    // ─── 400 Bad Request ──────────────────────────────────────────────────────

    @Test
    void post_missingMti_returns400() throws Exception {
        String json = """
            {"bit_003":"100000","bit_007":"0327162336","bit_011":"132256",
             "bit_012":"162338","bit_013":"0327","bit_042":"644400000000001",
             "bit_125":"000132256","bit_127":"000248756"}
            """;

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
        assertThat(gateway.received()).isFalse();
    }

    @Test
    void post_unknownMti_returns400() throws Exception {
        String json = "{\"mti\":\"9999\",\"bit_003\":\"100000\"}";

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
    }

    @Test
    void post_unsupportedMti0200_returns400() throws Exception {
        // MTI 0200 is valid ISO but not allowed for fire-and-forget dispatch
        String json = """
            {"mti":"0200","bit_003":"100000","bit_004":"000000001000",
             "bit_007":"0407123045","bit_011":"000001","bit_012":"123045",
             "bit_013":"0407","bit_032":"12345678901","bit_040":"006",
             "bit_041":"TERM0001","bit_042":"123456789012345","bit_049":"986"}
            """;

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
        assertThat(gateway.received()).isFalse();
    }

    @Test
    void post_missingRequiredBit003_returns400() throws Exception {
        String json = """
            {"mti":"0600","bit_007":"0327162336","bit_011":"132256",
             "bit_012":"162338","bit_013":"0327","bit_042":"644400000000001",
             "bit_125":"000132256","bit_127":"000248756"}
            """;

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
    }

    @Test
    void post_missingRequiredBit127_returns400() throws Exception {
        String json = """
            {"mti":"0600","bit_003":"100000","bit_007":"0327162336","bit_011":"132256",
             "bit_012":"162338","bit_013":"0327","bit_042":"644400000000001",
             "bit_125":"000132256"}
            """;

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
    }

    @Test
    void post_malformedJson_returns400() throws Exception {
        assertThat(post("/comunicacao/sessions/s1/messages", "{ not json }").statusCode())
            .isEqualTo(400);
    }

    @Test
    void post_unknownJsonField_returns400() throws Exception {
        String json = "{\"mti\":\"0600\",\"foo\":\"bar\"}";

        assertThat(post("/comunicacao/sessions/s1/messages", json).statusCode()).isEqualTo(400);
    }

    @Test
    void post_emptyBody_returns400() throws Exception {
        assertThat(post("/comunicacao/sessions/s1/messages", "").statusCode()).isEqualTo(400);
    }

    // ─── 404 Not Found ────────────────────────────────────────────────────────

    @Test
    void post_noActiveConnection_returns404() throws Exception {
        gateway.toThrow = new NoActiveConnectionException();

        assertThat(post("/comunicacao/sessions/s1/messages", validProbeJson()).statusCode())
            .isEqualTo(404);
    }

    // ─── 429 Too Many Requests ────────────────────────────────────────────────

    @Test
    void post_queueFull_returns429() throws Exception {
        gateway.toThrow = new ConnectionQueueFullException("conn-1");

        assertThat(post("/comunicacao/sessions/s1/messages", validProbeJson()).statusCode())
            .isEqualTo(429);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String validProbeJson() {
        return """
            {
              "mti": "0600",
              "bit_003": "100000",
              "bit_007": "0327162336",
              "bit_011": "132256",
              "bit_012": "162338",
              "bit_013": "0327",
              "bit_032": "00101000000",
              "bit_041": "GT000001",
              "bit_042": "644400000000001",
              "bit_125": "000132256",
              "bit_127": "000248756"
            }
            """;
    }
}
