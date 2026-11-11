package com.safeschoolroad.guide;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 하루 한도에 닿은 모델은 건너뛰고 다음 모델로
class LlmModelsTests {

    /** quotaModels 에 있는 모델로 부르면 429 를 낸다 */
    private static ChatModel fake(Set<String> quotaModels, List<String> calledWith) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String m = prompt.getOptions().getModel();
                calledWith.add(m);
                if (quotaModels.contains(m)) {
                    throw new RuntimeException("HTTP 429 - RESOURCE_EXHAUSTED: You exceeded your current quota");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("답:" + m))));
            }
        };
    }

    @Test
    void 한도에_닿으면_다음_모델로_넘어가고_그날은_건너뛴다() {
        List<String> called = new ArrayList<>();
        ChatModel chat = fake(Set.of("a"), called);
        var models = chain(chat, "a", "b", "c");

        assertThat(models.call("s", "u").content()).isEqualTo("답:b");
        assertThat(models.call("s", "u").model()).isEqualTo("p:b");
        assertThat(called).containsExactly("a", "b", "b"); // 두 번째부터는 a 를 부르지 않는다
        assertThat(models.available()).containsExactly("p:b", "p:c");
    }

    @Test
    void 가벼운_모델부터_쓸_수_있다() {
        List<String> called = new ArrayList<>();
        assertThat(chain(fake(Set.of(), called), "a", "b", "c").call("s", "u", true).model()).isEqualTo("p:c");
    }

    @Test
    void 모든_모델이_한도면_오류를_던지고_한도가_아닌_오류는_바로_던진다() {
        var models = chain(fake(Set.of("a", "b"), new ArrayList<>()), "a", "b");
        assertThatThrownBy(() -> models.call("s", "u")).hasMessageContaining("429");
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("인증키 오류");
            }
        };
        assertThatThrownBy(() -> chain(broken, "x", "y").call("s", "u")).hasMessageContaining("인증키");
    }

    @Test
    void 키가_없는_회사는_순서에서_빠진다() {
        var models = new LlmModels("groq:qwen,gemini:flash", "", "gemini-key", "https://example.invalid");
        assertThat(models.available()).containsExactly("gemini:flash");
        assertThat(new LlmModels("groq:qwen", "", "", "https://example.invalid").isAvailable()).isFalse();
    }

    @Test
    void 분당_한도는_짧게_기다렸다_같은_모델로_다시_부른다() {
        List<String> called = new ArrayList<>();
        int[] fails = {1};
        ChatModel chat = prompt -> {
            String m = prompt.getOptions().getModel();
            called.add(m);
            if (m.equals("a") && fails[0]-- > 0) {
                throw new RuntimeException("HTTP 429 - Rate limit reached for model `a` on tokens per minute (TPM): "
                        + "Limit 8000, Used 7900. Please try again in 0.1s.");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("답:" + m))));
        };
        assertThat(chain(chat, "a", "b").call("s", "u").model()).isEqualTo("p:a");
        assertThat(called).containsExactly("a", "a");
    }

    @Test
    void 분당_한도로_오래_기다려야_하면_그동안만_건너뛴다() {
        var clock = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-10T03:00:00Z"));
        java.time.Clock c = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.of("Asia/Seoul"); }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public java.time.Instant instant() { return clock.get(); }
        };
        ChatModel chat = prompt -> {
            String m = prompt.getOptions().getModel();
            if (m.equals("a")) {
                throw new RuntimeException("429 Rate limit reached on requests per minute (RPM). Please try again in 1m2.5s.");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("답:" + m))));
        };
        var models = new LlmModels(List.of(new LlmModels.Target("p", "a", chat), new LlmModels.Target("p", "b", chat)), c);
        assertThat(models.call("s", "u").model()).isEqualTo("p:b");
        assertThat(models.available()).containsExactly("p:b");
        clock.set(clock.get().plusSeconds(63));
        assertThat(models.available()).containsExactly("p:a", "p:b");
    }

    @Test
    void 하루_한도는_알려_준_시간까지_쉬고_없으면_자정까지() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-10T03:00:00Z")); // 한국 12시
        java.time.Clock c = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.of("Asia/Seoul"); }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        ChatModel chat = prompt -> {
            String m = prompt.getOptions().getModel();
            if (m.equals("groq")) {
                throw new RuntimeException("429 on tokens per day (TPD): Limit 200000. Please try again in 21m17.8s.");
            }
            if (m.equals("gemini")) {
                throw new RuntimeException("429 RESOURCE_EXHAUSTED: You exceeded your current quota");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("답:" + m))));
        };
        var models = new LlmModels(List.of(new LlmModels.Target("p", "groq", chat), new LlmModels.Target("p", "gemini", chat),
                new LlmModels.Target("p", "c", chat)), c);
        assertThat(models.call("s", "u").model()).isEqualTo("p:c");
        now.set(now.get().plusSeconds(22 * 60));
        assertThat(models.available()).containsExactly("p:groq", "p:c");
        now.set(java.time.Instant.parse("2026-10-10T15:00:01Z")); // 한국 다음 날 0시
        assertThat(models.available()).containsExactly("p:groq", "p:gemini", "p:c");
    }

    @Test
    void 다시_시도할_시간을_읽는다() {
        assertThat(LlmModels.retryAfterSeconds(new RuntimeException("Please try again in 1m2.5s."))).isEqualTo(62.5);
        assertThat(LlmModels.retryAfterSeconds(new RuntimeException("Please try again in 600ms."))).isEqualTo(0.6);
        assertThat(LlmModels.retryAfterSeconds(new RuntimeException("Please try again in 13.9s."))).isEqualTo(13.9);
        assertThat(LlmModels.retryAfterSeconds(new RuntimeException("no hint"))).isEqualTo(-1);
        assertThat(LlmModels.perMinute(new RuntimeException("tokens per day (TPD)"))).isFalse();
    }

    @Test
    void 순서에_답_길이를_적을_수_있다() {
        var models = new LlmModels("groq:qwen/qwen3.8-27b|900,gemini:flash", "groq-key", "gemini-key", "https://example.invalid");
        assertThat(models.available()).containsExactly("groq:qwen/qwen3.8-27b", "gemini:flash");
    }

    @Test
    void 정한_답_길이를_요청에_넣는다() {
        List<Integer> max = new ArrayList<>();
        ChatModel chat = prompt -> {
            max.add(prompt.getOptions().getMaxTokens());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("답"))));
        };
        new LlmModels(List.of(new LlmModels.Target("p", "a", chat, 900))).call("s", "u");
        assertThat(max).containsExactly(900);
    }

    @Test
    void 요청이_한도보다_크면_기다리지_않고_다음_모델로() {
        List<String> called = new ArrayList<>();
        ChatModel chat = prompt -> {
            String m = prompt.getOptions().getModel();
            called.add(m);
            if (m.equals("a")) {
                throw new RuntimeException("429 Request too large for model `a` on output tokens per minute (OTPM): Limit 1000, Requested 1020.");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("답:" + m))));
        };
        var models = chain(chat, "a", "b");
        assertThat(models.call("s", "u").model()).isEqualTo("p:b");
        assertThat(models.available()).containsExactly("p:a", "p:b"); // 쉬게 하지 않음
        assertThat(called).containsExactly("a", "b");
    }

    private static LlmModels chain(ChatModel chat, String... names) {
        return new LlmModels(java.util.Arrays.stream(names).map(n -> new LlmModels.Target("p", n, chat)).toList());
    }
}
