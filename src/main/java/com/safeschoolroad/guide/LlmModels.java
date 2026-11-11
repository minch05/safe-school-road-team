package com.safeschoolroad.guide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 을 정해진 순서로 쓰기 (2026-10-10). 모두 무료 API 이고, 로컬 모델은 쓰지 않는다(CLAUDE.md 결정).
 *
 * 순서는 설정 app.llm.chain 으로 바꾼다. 모양: "회사:모델|최대토큰,회사:모델" (회사는 groq 또는 gemini, |최대토큰은 생략 가능).
 * Groq 를 메인으로 쓰고(하루 한도가 커서 같은 모델로 일관되게 나옴), 한도에 닿을 때만 다음 모델로 넘어간다.
 *  - Groq: 모델마다 하루 1,000번·20만 토큰, 분당 30번·8,000토큰, qwen 은 분당 출력 1,000토큰 (카드 등록 없음)
 *    안내문 하나에 약 2,000토큰이라 실제로는 모델마다 하루 약 100개 (2026-10-10 시험)
 *  - Gemini 무료: 모델마다 하루 약 20번
 * 한도(429)에 닿으면 오류에 적힌 시간까지 그 모델을 쉬게 한다(적혀 있지 않은 하루 한도는 한국 시간 자정까지).
 * 쉬는 시간이 20초 이하면 기다렸다 같은 모델로 다시 부르고(같은 모델로 일관되게), 더 길면 다음 모델로 넘어간다. 키가 없는 회사는 순서에서 뺀다.
 * 모든 모델이 한도면 정해진 문장(TemplateGuide)으로 만든다.
 * 모델은 50개 경로 품질 시험으로 고른다(docs/ai-guide-evaluation-20261010.md).
 */
@Component
public class LlmModels {

    private static final Logger log = LoggerFactory.getLogger(LlmModels.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 순서 하나: 회사 이름, 모델 이름, 그 회사에 연결된 클라이언트 */
    /** maxTokens: 답의 최대 길이(토큰). null 이면 회사 기본값 */
    public record Target(String provider, String model, ChatModel client, Integer maxTokens) {
        public Target(String provider, String model, ChatModel client) {
            this(provider, model, client, null);
        }

        String id() {
            return provider + ":" + model;
        }
    }

    // 분당 한도에 닿았을 때 이만큼 이하로 기다리라고 하면 같은 모델로 한 번 더 부른다(같은 모델로 일관되게).
    // Groq qwen 은 분당 출력 1,000토큰(OTPM)이라 안내문 3~4개마다 십몇 초 기다리라고 한다(2026-10-10 확인)
    private static final double SHORT_WAIT_SEC = 20;
    private static final Pattern RETRY_IN = Pattern.compile("try again in (?:(\\d+)m(?!s))?(\\d+(?:\\.\\d+)?)(ms|s)");

    private final List<Target> targets;
    // 한도에 닿은 모델 → 다시 쓸 수 있는 때 (하루 한도면 다음 날 0시, 분당 한도면 1분 뒤)
    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LlmModels(
            @Value("${app.llm.chain:groq:qwen/qwen3.8-27b|900,groq:openai/gpt-oss-120b|3000,gemini:gemini-3.5-flash,gemini:gemini-3.6-flash}") String chain,
            @Value("${GROQ_API_KEY:}") String groqKey,
            @Value("${LLM_API_KEY:}") String geminiKey,
            @Value("${LLM_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}") String geminiUrl) {
        Map<String, ChatModel> clients = new HashMap<>();
        if (!groqKey.isBlank()) {
            clients.put("groq", client("https://api.groq.com/openai", "/v1/chat/completions", groqKey));
        }
        if (!geminiKey.isBlank()) {
            clients.put("gemini", client(geminiUrl, "/chat/completions", geminiKey));
        }
        List<Target> list = new ArrayList<>();
        for (String entry : chain.split(",")) {
            String e = entry.strip();
            int colon = e.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            ChatModel c = clients.get(e.substring(0, colon));
            if (c != null) {
                // "모델|900" 이면 답 길이를 900토큰으로 제한. Groq qwen 은 분당 출력 1,000토큰이라 이 값을 정하지 않으면
                // Groq 가 답 길이를 크게 잡아 "요청이 너무 크다"(429)로 거절한다 (2026-10-10)
                String model = e.substring(colon + 1);
                Integer max = null;
                int bar = model.indexOf('|');
                if (bar > 0) {
                    max = Integer.valueOf(model.substring(bar + 1).strip());
                    model = model.substring(0, bar);
                }
                list.add(new Target(e.substring(0, colon), model, c, max));
            }
        }
        this.targets = List.copyOf(list);
        this.clock = Clock.system(KST);
        log.info("LLM 순서: {}", targets.stream().map(Target::id).toList());
    }

    /** 시험용 */
    public LlmModels(List<Target> targets) {
        this(targets, Clock.system(KST));
    }

    LlmModels(List<Target> targets, Clock clock) {
        this.targets = List.copyOf(targets);
        this.clock = clock;
    }

    // OpenAI 호환 주소 하나에 연결. 시간 제한을 두고, 일시 오류(5xx)만 짧게 다시 시도한다(429 는 다음 모델로)
    private static ChatModel client(String baseUrl, String path, String key) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(40));
        RestClient.Builder rest = RestClient.builder().requestFactory(factory)
                // Groq 는 기본 User-Agent 를 막는 경우가 있다(시험에서 403)
                .defaultHeader("User-Agent", "safe-school-road/1.0");
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).completionsPath(path).apiKey(key)
                .restClientBuilder(rest).build();
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(3).retryOn(TransientAiException.class)
                .exponentialBackoff(2000, 2, 8000).build();
        return OpenAiChatModel.builder().openAiApi(api).retryTemplate(retry)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.3).build()).build();
    }

    public boolean isAvailable() {
        return !targets.isEmpty();
    }

    public record Reply(String content, String model) {
    }

    /** 한도가 남은 모델로 부른다. 모든 모델이 한도면 마지막 오류를 던진다 */
    public Reply call(String system, String user) {
        return call(system, user, false);
    }

    /**
     * @param preferLight true 면 순서 뒤부터 쓴다. 경로 차이 설명처럼 자주 부르는 곳이 메인 모델 한도를 먼저 쓰지 않게.
     */
    public Reply call(String system, String user, boolean preferLight) {
        RuntimeException last = null;
        List<Target> order = new ArrayList<>(targets);
        if (preferLight) {
            Collections.reverse(order);
        }
        for (Target t : order) {
            // 분당 한도로 잠깐 막힌 모델은 기다렸다 다시 부른다(최대 3번). 오래 막혔거나 하루 한도면 다음 모델로
            for (int tryNo = 0; tryNo < 3; tryNo++) {
                double left = blockedSeconds(t.id());
                if (left > SHORT_WAIT_SEC) {
                    break;
                }
                if (left > 0) {
                    sleep(left);
                }
                try {
                    return new Reply(ask(t, system, user), t.id());
                } catch (RuntimeException e) {
                    if (!isQuota(e)) {
                        throw e;
                    }
                    last = e;
                    if (messages(e).contains("Request too large")) {
                        // 기다려도 풀리지 않는다(요청 하나가 한도보다 큼). 쉬게 하지 않고 이번만 다음 모델로
                        log.warn("{} 요청이 한도보다 커서 다음 모델로 넘어갑니다. 최대 토큰(|숫자)을 줄이세요", t.id());
                        break;
                    }
                    // Groq 는 분당·하루 한도 모두 "try again in 21m17s"처럼 풀리는 때를 알려 준다(하루 한도도 24시간 구간).
                    // 알려 주지 않으면 분당은 1분, 하루는 한국 시간 자정까지 쉰다(Gemini 무료 하루 한도)
                    double wait = retryAfterSeconds(e);
                    Instant until = wait >= 0 ? clock.instant().plusMillis((long) (wait * 1000))
                            : perMinute(e) ? clock.instant().plusSeconds(60)
                            : LocalDate.now(clock).plusDays(1).atStartOfDay(KST).toInstant();
                    blockedUntil.put(t.id(), until);
                    log.info("{} {} 한도, {} 까지 쉼", t.id(), perMinute(e) ? "분당" : "하루", until);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("쓸 수 있는 LLM 모델이 없습니다");
    }

    private static String ask(Target t, String system, String user) {
        return ChatClient.create(t.client()).prompt()
                .options(OpenAiChatOptions.builder().model(t.model()).temperature(0.3).maxTokens(t.maxTokens()).build())
                .system(system)
                .user(user)
                .call()
                .content();
    }

    private boolean blocked(String id) {
        return blockedSeconds(id) > 0;
    }

    private double blockedSeconds(String id) {
        Instant until = blockedUntil.get(id);
        return until == null ? 0 : Math.max(0, Duration.between(clock.instant(), until).toMillis() / 1000.0);
    }

    public List<String> available() {
        return targets.stream().map(Target::id).filter(id -> !blocked(id)).toList();
    }

    // Groq: "... on tokens per minute (TPM) ... Please try again in 1.23s", Gemini: "...PerMinute..."
    static boolean perMinute(Throwable e) {
        String m = messages(e).toLowerCase();
        return m.contains("per minute") || m.contains("perminute");
    }

    /** 오류 문장에 적힌 "다시 시도할 때까지 초". 없으면 -1 */
    static double retryAfterSeconds(Throwable e) {
        Matcher m = RETRY_IN.matcher(messages(e));
        if (!m.find()) {
            return -1;
        }
        double sec = Double.parseDouble(m.group(2)) / (m.group(3).equals("ms") ? 1000 : 1);
        return (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 60) + sec;
    }

    private static String messages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000) + 300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean isQuota(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("exceeded your current quota")
                    || msg.contains("rate_limit_exceeded") || msg.contains("Rate limit reached")) {
                return true;
            }
        }
        return false;
    }
}
