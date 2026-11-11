package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.evidence.RouteEvidence.KeyPoint;
import com.safeschoolroad.saved.TemplateGuide;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GuideWriter {
    private final GuideAiClient client;
    private final ObjectMapper mapper;

    public GuideWriter(GuideAiClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** Deliberately excludes coordinates, original IDs, route names and account information. */
    public record Input(int seq, String kind, String name, String reason, String signalStatus,
                        boolean isLast, String templateText) { }
    public record Result(List<String> texts, String source) { }

    public Result write(List<KeyPoint> points) {
        List<Input> inputs = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            KeyPoint point = points.get(i);
            int nth = seen.merge(point.kind() + "|" + point.reason(), 1, Integer::sum) - 1;
            inputs.add(new Input(point.seq(), point.kind(), point.name(), point.reason(), point.signalStatus(),
                    i == points.size() - 1, TemplateGuide.sentence(point, nth, i == points.size() - 1)));
        }
        Result fallback = new Result(inputs.stream().map(Input::templateText).toList(), TemplateGuide.SOURCE);
        if (!client.configured() || inputs.isEmpty() || inputs.size() > 30) return fallback;
        try {
            JsonNode root = mapper.readTree(client.generate(List.copyOf(inputs)));
            JsonNode replies = root.path("points");
            if (!replies.isArray() || replies.size() != inputs.size()) return fallback;
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                Input input = inputs.get(i);
                JsonNode reply = replies.get(i);
                JsonNode sequence = reply.path("seq");
                JsonNode text = reply.path("text");
                if (!sequence.isIntegralNumber() || !sequence.canConvertToInt() || sequence.intValue() != input.seq()
                        || !text.isTextual() || !GuideTextValidator.valid(input, text.asText())) return fallback;
                texts.add(text.asText().strip());
            }
            return new Result(List.copyOf(texts), "AI");
        } catch (Exception failure) {
            // Provider exceptions can contain credentials or request bodies. Never expose them.
            return fallback;
        }
    }
}
