package com.picsou.adapter;

import com.picsou.port.TransactionCategorizerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link TransactionCategorizerPort} backed by Spring AI's {@link ChatClient}. The underlying
 * {@link ChatModel} (Ollama / OpenAI-compatible / Anthropic) is chosen by configuration
 * ({@code spring.ai.model.chat}); this adapter is provider-agnostic. The model is asked to pick
 * one of the member's category slugs and report a confidence; the structured JSON answer is parsed
 * by Spring AI's bean output converter. Any failure (provider down, unparseable answer) degrades to
 * a {@link CategorizationResult} with {@code status="ERROR"} so the categorization pipeline never
 * breaks. Token usage, the prompt sent, and the raw response text are captured for audit logging.
 */
public class SpringAiCategorizer implements TransactionCategorizerPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCategorizer.class);

    private static final String SYSTEM = """
        You categorize a single bank transaction for a personal-finance app.
        Choose exactly ONE category from the provided list, using only the given category slugs.
        Decide primarily from the merchant name. If none clearly fits, use the slug "unknown".
        Return the chosen slug and a confidence between 0 and 1.""";

    private static final BeanOutputConverter<Answer> OUTPUT = new BeanOutputConverter<>(Answer.class);

    private final ChatClient chatClient;

    public SpringAiCategorizer(ChatModel chatModel) {
        this.chatClient = ChatClient.create(chatModel);
    }

    /** The model's structured answer; parsed from JSON by Spring AI's BeanOutputConverter. */
    public record Answer(String categorySlug, double confidence) {}

    @Override
    public CategorizationResult categorize(
        CategorizationInput input,
        List<CategoryOption> categories,
        List<Example> examples
    ) {
        if (categories.isEmpty()) {
            return CategorizationResult.empty("EMPTY");
        }
        String fullPrompt = null;
        long t0 = System.nanoTime();
        try {
            // The JSON format instructions are part of what the model sees, so of the audit too.
            String userPrompt = buildUserPrompt(input, categories, examples) + "\n" + OUTPUT.getFormat();
            fullPrompt = SYSTEM + "\n\n" + userPrompt;
            // Not responseEntity(): it parses getResult(), the FIRST generation, which for a
            // thinking model (Claude Haiku 5.5 thinks by default) is an empty thinking block.
            ChatResponse resp = chatClient.prompt()
                .system(SYSTEM)
                .user(userPrompt)
                .call()
                .chatResponse();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            Integer pt = null, ct = null, tt = null;
            if (resp != null) {
                var meta = resp.getMetadata();
                if (meta != null) {
                    var u = meta.getUsage();
                    if (u != null) {
                        pt = asInt(u.getPromptTokens());
                        ct = asInt(u.getCompletionTokens());
                        tt = asInt(u.getTotalTokens());
                    }
                }
            }
            String respText = answerText(resp);
            if (respText == null) {
                return new CategorizationResult(Optional.empty(), fullPrompt, null, pt, ct, tt, ms,
                    "ERROR", "The model returned no text answer.");
            }
            Answer answer = OUTPUT.convert(respText);

            Optional<CategorySuggestion> sug = toSuggestion(answer);
            return new CategorizationResult(sug, fullPrompt, respText, pt, ct, tt, ms,
                sug.isPresent() ? "OK" : "EMPTY", null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.warn("AI categorization failed for '{}': {}", input.merchantLabel(), e.getMessage());
            return new CategorizationResult(Optional.empty(), fullPrompt, null, null, null, null, ms, "ERROR",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * The model's answer: the first non-thinking generation carrying non-blank text, or
     * {@code null}. Spring AI maps every Anthropic content block to a generation, so a thinking
     * block comes first: its text is empty under the default {@code display: omitted}, but holds
     * a reasoning summary under {@code display: summarized}. Spring AI tags those generations with
     * a {@code signature} property ({@code data} for a redacted one), which is how they are skipped.
     */
    public static String answerText(ChatResponse resp) {
        if (resp == null) return null;
        for (Generation g : resp.getResults()) {
            AssistantMessage out = g.getOutput();
            if (out == null || isThinking(out)) continue;
            String text = out.getText();
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    private static boolean isThinking(AssistantMessage out) {
        Map<String, Object> props = out.getMetadata();
        return props != null && (props.containsKey("signature") || props.containsKey("data"));
    }

    /** Extract slug-normalize/unknown/empty logic from the model's raw answer. */
    private Optional<CategorySuggestion> toSuggestion(Answer answer) {
        if (answer == null) {
            return Optional.empty();
        }
        String slug = answer.categorySlug() == null
            ? "" : answer.categorySlug().trim().toLowerCase(Locale.ROOT);
        if (slug.isEmpty() || slug.equals("unknown")) {
            return Optional.empty();
        }
        return Optional.of(new CategorySuggestion(slug, answer.confidence()));
    }

    /** Null-safe conversion of a {@link Number} to {@link Integer}. */
    private static Integer asInt(Number n) {
        return n == null ? null : n.intValue();
    }

    private static String buildUserPrompt(
        CategorizationInput input,
        List<CategoryOption> categories,
        List<Example> examples
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Categories (slug = name):\n");
        for (CategoryOption c : categories) {
            sb.append("- ").append(c.slug()).append(" = ").append(c.name()).append('\n');
        }
        if (!examples.isEmpty()) {
            sb.append("\nThis member's past choices (merchant => slug):\n");
            for (Example e : examples) {
                sb.append("- \"").append(e.merchantLabel()).append("\" => ").append(e.categorySlug()).append('\n');
            }
        }
        sb.append("\nTransaction to categorize:\n");
        sb.append("- merchant: \"").append(nullToEmpty(input.merchantLabel())).append("\"\n");
        sb.append("- description: \"").append(nullToEmpty(input.description())).append("\"\n");
        sb.append("- amount: ").append(input.amount()).append(" EUR\n");
        sb.append("\nReply with the single best category slug from the list above (or \"unknown\").");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
