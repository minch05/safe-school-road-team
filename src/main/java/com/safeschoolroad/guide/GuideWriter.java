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
    public enum Tone { LOWER, UPPER }
    public record Result(List<String> texts, String source, boolean attempted) { }

    public Result write(List<KeyPoint> points) {
        return write(points, Tone.LOWER);
    }

    public Result write(List<KeyPoint> points, Tone tone) {
        List<Input> inputs = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            KeyPoint point = points.get(i);
            int nth = seen.merge(point.kind() + "|" + point.reason(), 1, Integer::sum) - 1;
            inputs.add(new Input(point.seq(), point.kind(), point.name(), point.reason(), point.signalStatus(),
                    i == points.size() - 1, template(point, nth, i == points.size() - 1, tone)));
        }
        boolean attempted = client.configured() && !inputs.isEmpty() && inputs.size() <= 30;
        Result fallback = new Result(inputs.stream().map(Input::templateText).toList(), TemplateGuide.SOURCE, attempted);
        if (!attempted) return fallback;
        try {
            List<Input> immutable = List.copyOf(inputs);
            JsonNode root = mapper.readTree(tone == Tone.UPPER ? client.generate(immutable, tone) : client.generate(immutable));
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
            return new Result(List.copyOf(texts), "AI", true);
        } catch (Exception failure) {
            // Provider exceptions can contain credentials or request bodies. Never expose them.
            return fallback;
        }
    }
    private static String template(KeyPoint point, int occurrence, boolean last, Tone tone) {
        if (tone == Tone.UPPER && "CROSSWALK".equals(point.kind())) {
            return "횡단보도 앞에서 멈춰 신호와 양쪽을 확인한 뒤 건너요.";
        }
        return TemplateGuide.sentence(point, occurrence, last);
    }
}
