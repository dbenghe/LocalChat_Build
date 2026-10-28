package localchat;

import java.io.IOException;
import java.util.List;

/**
 * Tiny streamed probe (1.28.8) that finds out, for one model, whether it thinks by default and whether an off parameter really works.
 * Two requests at most, each max_tokens 32 with a one-line prompt: one without the off parameter, one with the candidate. Thinking is detected from
 * reasoning_content/reasoning deltas, usage reasoning tokens, a Mistral thinking chunk or an inline think tag. Streaming keeps bytes flowing, so a proxy
 * with a ~100 s limit cannot cut it. The result is never a guess: an error leaves the knowledge unchanged.
 */
final class ThinkingProbe {
    enum Outcome {
        /** Thinks by default and the candidate parameter stopped it. */
        OFF_OK,
        /** Thinks by default and the candidate parameter changed nothing. */
        OFF_IGNORED,
        /** Thinks by default and the server answered 400/422 to the candidate parameter. */
        OFF_REJECTED,
        /** Showed no reasoning without any parameter. */
        NO_REASONING,
        /** Network/server problem: nothing is learned. */
        ERROR
    }

    record Observation(boolean thinks, int status, String error) {
        boolean failed() {
            return error != null;
        }
    }

    record Result(Outcome outcome, ThinkingKnowledge.Info info, String detail) {}

    static final int MAX_TOKENS = 32;
    static final String PROMPT = "What is 17 * 23? Answer with the number only.";
    static final String SYSTEM = "You are a concise assistant.";
    static final int TIMEOUT_MILLIS = 90_000;

    private ThinkingProbe() {}

    /** Pure decision table, also used by the tests. {@code off} is null when the first request already showed no reasoning. */
    static Result classify(Observation base, Observation off, ThinkingControl.Style style) {
        long now = System.currentTimeMillis();
        if (base.failed()) return new Result(Outcome.ERROR, null, base.error());
        if (!base.thinks()) return new Result(Outcome.NO_REASONING, new ThinkingKnowledge.Info(Boolean.FALSE, null, now), "");
        if (off == null) return new Result(Outcome.ERROR, null, "no second request");
        if (off.status() == 400 || off.status() == 422)
            return new Result(Outcome.OFF_REJECTED, new ThinkingKnowledge.Info(Boolean.TRUE, ThinkingKnowledge.NO_OFF, now), "HTTP " + off.status());
        if (off.failed()) return new Result(Outcome.ERROR, null, off.error());
        if (!off.thinks()) return new Result(Outcome.OFF_OK, new ThinkingKnowledge.Info(Boolean.TRUE, style.id, now), "");
        return new Result(Outcome.OFF_IGNORED, new ThinkingKnowledge.Info(Boolean.TRUE, ThinkingKnowledge.NO_OFF, now), "");
    }

    /** Runs the probe. Blocking: call from a background thread. Candidate null = reasoning_effort=none. */
    static Result run(Provider provider, String baseUrl, String apiKey, String model, ThinkingControl.Style candidate) {
        ThinkingControl.Style style = candidate != null ? candidate : ThinkingControl.Style.EFFORT_NONE;
        Observation base = observe(provider, baseUrl, apiKey, model, null);
        if (base.failed() || !base.thinks()) return classify(base, null, style);
        return classify(base, observe(provider, baseUrl, apiKey, model, style), style);
    }

    static Observation observe(Provider provider, String baseUrl, String apiKey, String model, ThinkingControl.Style style) {
        ChatClient client = new ChatClient();
        client.budgetMillis(TIMEOUT_MILLIS);
        client.outputLimit(MAX_TOKENS);
        client.forceThinking(style);
        StringBuilder visible = new StringBuilder();
        boolean[] reasoning = {false};
        try {
            ChatClient.Reply reply = client.chat(provider, baseUrl, apiKey, model, SYSTEM, List.of(ChatClient.Message.user(PROMPT)), List.of(), true, new ChatClient.Listener() {
                public void onText(String delta) {
                    visible.append(delta);
                }

                public void onReasoning(String delta) {
                    if (delta != null && !delta.isEmpty()) reasoning[0] = true;
                }
            });
            boolean thinks = reasoning[0] || !reply.reasoning.isEmpty() || client.usage().reasoning() > 0
                    || visible.toString().toLowerCase(java.util.Locale.ROOT).contains("<think") || reply.text.toLowerCase(java.util.Locale.ROOT).contains("<think");
            return new Observation(thinks, 200, null);
        } catch (ChatClient.HttpFailure failure) {
            return new Observation(false, failure.status, failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return new Observation(false, 0, String.valueOf(failure.getMessage()));
        }
    }
}
