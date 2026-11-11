package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.evidence.RouteEvidence.KeyPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class GuideWriterTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private GuideAiClient client;
    private GuideWriter writer;
    private static final KeyPoint CROSSING = new KeyPoint(1, "CROSSWALK", "", "CROSSING",
            37.59, 127.08, 10, "UNKNOWN", "공공자료", "original-id");
    private static final String SAFE = "횡단보도 앞에서 멈춰요. 신호와 양쪽을 확인하고 건너요.";

    @BeforeEach void setup() {
        client = mock(GuideAiClient.class);
        writer = new GuideWriter(client, mapper);
        when(client.configured()).thenReturn(true);
    }

    @Test void absentKeyUsesOriginalTemplateWithoutCallingProvider() throws Exception {
        when(client.configured()).thenReturn(false);
        var result = writer.write(List.of(CROSSING));
        assertEquals("TEMPLATE", result.source());
        assertTrue(result.texts().getFirst().contains("멈춰"));
        verify(client, never()).generate(anyList());
    }

    @Test void validAiTextIsAcceptedAndOnlyPublicFactsAreSent() throws Exception {
        when(client.generate(anyList())).thenAnswer(invocation -> {
            List<GuideWriter.Input> sent = invocation.getArgument(0);
            var json = mapper.valueToTree(sent).get(0);
            List<String> fields = new ArrayList<>();
            json.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("seq", "kind", "name", "reason", "signalStatus", "isLast", "templateText"), fields);
            return reply(SAFE);
        });
        var result = writer.write(List.of(CROSSING));
        assertEquals("AI", result.source());
        assertEquals(List.of(SAFE), result.texts());
        verify(client).generate(anyList());
    }

    @Test void providerTimeoutOrFailurePreservesTemplate() throws Exception {
        when(client.generate(anyList())).thenThrow(new ResourceAccessException("timeout"));
        assertEquals("TEMPLATE", writer.write(List.of(CROSSING)).source());
    }

    @Test void malformedTruncatedMissingDuplicateAndWrongSequenceUseTemplate() throws Exception {
        for (String output : List.of("not JSON", "null", "{", "{\"points\":[]}",
                "{\"points\":[{\"seq\":1,\"text\":null}]}",
                "{\"points\":[{\"seq\":1.0,\"text\":\"text\"}]}",
                "{\"points\":[{\"seq\":4294967297,\"text\":\"text\"}]}",
                "{\"points\":[{\"seq\":2,\"text\":\"text\"}]}",
                "{\"points\":[{\"seq\":1,\"text\":\"text\"},{\"seq\":1,\"text\":\"text\"}]}")) {
            when(client.generate(anyList())).thenReturn(output);
            assertEquals("TEMPLATE", writer.write(List.of(CROSSING)).source(), output);
        }
    }

    @Test void unsafeLandmarksSignalsDirectionsAndPromisesAreRejected() throws Exception {
        for (String bad : List.of(
                "횡단보도에서 초록불이면 건너요.", "횡단보도에서 신호만 보고 건너요.",
                "횡단보도 앞에서 멈춰요. 신호와 양쪽을 확인하고 오른쪽으로 가요.",
                "횡단보도 앞에서 멈춰요. 신호와 양쪽을 보고 행복마트로 가요.",
                SAFE + " 안전한 길이에요.", SAFE + " 20미터 걸어요.",
                "횡단보도 앞에서 멈추지 말고 신호와 양쪽을 확인하고 건너요.",
                "횡단보도 앞에서 멈춰요. 신호와 양쪽을 확인하지 않고 건너요.",
                "횡단보도 앞에서 멈춰요. 신호와 양쪽을 보고 달려가요.",
                SAFE + " CCTV가 지켜 줘요.",
                "횡단보도 앞에서 멈춰요. 신호와 양쪽을 보고 왼쪽으로 꺾어요.")) {
            when(client.generate(anyList())).thenReturn(reply(bad));
            assertEquals("TEMPLATE", writer.write(List.of(CROSSING)).source(), bad);
        }
    }

    @Test void turnLandmarkAndLastPointRulesAreChecked() {
        var point = new GuideWriter.Input(2, "CCTV", "", "TURN", null, false, "");
        assertTrue(GuideTextValidator.valid(point, "CCTV가 있는 곳에서 길을 꺾어요. 지도의 선을 따라가요."));
        assertFalse(GuideTextValidator.valid(point, "CCTV를 지나가요."));
        assertFalse(GuideTextValidator.valid(point, "CCTV가 있는 곳에서 길을 꺾어요. 거의 다 왔어요."));
        assertFalse(GuideTextValidator.valid(point, "편의점 쪽에서 길을 꺾어요."));
        var school = new GuideWriter.Input(3, "SCHOOL", "망우초등학교", "GAP", null, true, "");
        assertTrue(GuideTextValidator.valid(school, "망우초등학교 앞을 지나요. 거의 다 왔어요."));
        assertFalse(GuideTextValidator.valid(school, "다른초등학교 앞을 지나요."));
    }

    @Test void longRoutesKeepAllTemplatePointsWithoutSendingAnOversizedPrompt() throws Exception {
        assertEquals(31, writer.write(java.util.Collections.nCopies(31, CROSSING)).texts().size());
        verify(client, never()).generate(anyList());
    }

    private String reply(String text) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("points", List.of(java.util.Map.of("seq", 1, "text", text))));
    }
}
