package com.picsou.adapter;

import com.picsou.port.TransactionCategorizerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Locale;
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
            String userPrompt = buildUserPrompt(input, categories, examples);
            fullPrompt = SYSTEM + "\n\n" + userPrompt;
            var re = chatClient.prompt()
                .system(SYSTEM)
                .user(userPrompt)
                .call()
                .responseEntity(Answer.class);
            Answer answer = re.entity();
            ChatResponse resp = re.response();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            Integer pt = null, ct = null, tt = null;
            String respText = null;
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
                if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                    respText = resp.getResult().getOutput().getText();
                }
            }

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
