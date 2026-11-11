package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GuideAiClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private GuideAiClient client;
    private final List<GuideWriter.Input> inputs = List.of(
            new GuideWriter.Input(1, "CCTV", "", "GAP", null, true, "CCTV를 지나가요."));

    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GuideAiClient(builder.build(), mapper, "test-key", "test-model", true);
    }

    @Test void completionSendsAllowlistedInputsAndParsesCompletedContent() throws Exception {
        String content = "{\"points\":[{\"seq\":1,\"text\":\"CCTV를 지나가요.\"}]}";
        server.expect(requestTo(GuideAiClient.ENDPOINT)).andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(request -> {
                    var payload = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("test-model", payload.path("model").asText());
                    var facts = mapper.readTree(payload.path("messages").get(1).path("content").asText());
                    assertEquals(mapper.valueToTree(inputs), facts.path("points"));
                }).andRespond(withSuccess(mapper.writeValueAsString(Map.of("choices",
                        List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))),
                        MediaType.APPLICATION_JSON));
        assertEquals(content, client.generate(inputs));
        server.verify();
    }

    @Test void rateLimitKeepsDefaultGuideInsteadOfAbortingSave() {
        server.expect(requestTo(GuideAiClient.ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals("TEMPLATE", writer().write(points()).source());
        server.verify();
    }

    @Test void timeoutKeepsDefaultGuide() {
        server.expect(requestTo(GuideAiClient.ENDPOINT)).andRespond(withException(new SocketTimeoutException()));
        assertEquals("TEMPLATE", writer().write(points()).source());
        server.verify();
    }

    @Test void truncatedCompletionKeepsDefaultGuide() {
        server.expect(requestTo(GuideAiClient.ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"truncated\"}}]}",
                MediaType.APPLICATION_JSON));
        assertEquals("TEMPLATE", writer().write(points()).source());
        server.verify();
    }

    @Test void emptyKeyAndDisabledClientAreNotConfigured() {
        assertFalse(new GuideAiClient(RestClient.create(), mapper, "", "model", true).configured());
        assertFalse(new GuideAiClient(RestClient.create(), mapper, "key", "model", false).configured());
    }

    private GuideWriter writer() { return new GuideWriter(client, mapper); }
    private List<com.safeschoolroad.evidence.RouteEvidence.KeyPoint> points() {
        return List.of(new com.safeschoolroad.evidence.RouteEvidence.KeyPoint(
                1, "CCTV", "", "GAP", 37.59, 127.08, 100, null, "public", "id"));
    }
}
