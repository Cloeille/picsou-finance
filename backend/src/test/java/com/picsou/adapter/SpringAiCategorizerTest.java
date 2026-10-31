package com.picsou.adapter;

import com.picsou.port.TransactionCategorizerPort.CategorizationInput;
import com.picsou.port.TransactionCategorizerPort.CategoryOption;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiCategorizerTest {

    private static final List<CategoryOption> CATS = List.of(new CategoryOption("groceries", "Courses"));
    private static final CategorizationInput TX =
        new CategorizationInput("CARREFOUR", "CB CARREFOUR", new BigDecimal("-42.10"));

    private static ChatModel replying(String... blockTexts) {
        ChatModel model = mock(ChatModel.class);
        List<Generation> gens = java.util.Arrays.stream(blockTexts)
            .map(t -> new Generation(new AssistantMessage(t))).toList();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(gens));
        return model;
    }

    @Test
    void skipsTheEmptyThinkingBlockAThinkingModelSendsFirst() {
        // Claude Haiku 5.5 thinks by default: Spring AI maps the (display: omitted) thinking
        // block to a first generation with empty text, then the answer to a second one.
        var r = new SpringAiCategorizer(replying("", "{\"categorySlug\": \"groceries\", \"confidence\": 0.9}"))
            .categorize(TX, CATS, List.of());

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.suggestion()).get().satisfies(s -> {
            assertThat(s.categorySlug()).isEqualTo("groceries");
            assertThat(s.confidence()).isEqualTo(0.9);
        });
        assertThat(r.response()).contains("groceries");
    }

    @Test
    void skipsASummarizedThinkingBlockEvenThoughItCarriesText() {
        // Under display: summarized the thinking block holds prose; Spring AI tags it "signature".
        AssistantMessage thinking = AssistantMessage.builder()
            .content("The merchant looks like a supermarket.")
            .properties(Map.of("signature", "sig"))
            .build();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
            new Generation(thinking),
            new Generation(new AssistantMessage("{\"categorySlug\": \"groceries\", \"confidence\": 0.8}")))));

        var r = new SpringAiCategorizer(model).categorize(TX, CATS, List.of());

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.suggestion()).get().extracting(s -> s.categorySlug()).isEqualTo("groceries");
    }

    @Test
    void sendsTheJsonFormatInstructionsAuditsThemAndParsesAFencedAnswerWithUsage() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse resp = new ChatResponse(
            List.of(new Generation(new AssistantMessage(
                "```json\n{\"categorySlug\": \"groceries\", \"confidence\": 0.7}\n```"))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30)).build());
        when(model.call(any(Prompt.class))).thenReturn(resp);

        var r = new SpringAiCategorizer(model).categorize(TX, CATS, List.of());

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        String userText = sent.getValue().getUserMessage().getText();
        assertThat(userText).contains("CARREFOUR").contains("categorySlug").contains("JSON");
        assertThat(r.prompt()).contains(userText);
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.suggestion()).get().extracting(s -> s.categorySlug()).isEqualTo("groceries");
        assertThat(r.promptTokens()).isEqualTo(120);
        assertThat(r.completionTokens()).isEqualTo(30);
    }

    @Test
    void reportsAnErrorWhenNoBlockCarriesText() {
        var r = new SpringAiCategorizer(replying("")).categorize(TX, CATS, List.of());

        assertThat(r.status()).isEqualTo("ERROR");
        assertThat(r.suggestion()).isEmpty();
    }

    @Test
    void unknownSlugYieldsNoSuggestion() {
        var r = new SpringAiCategorizer(replying("{\"categorySlug\": \"unknown\", \"confidence\": 0.2}"))
            .categorize(TX, CATS, List.of());

        assertThat(r.status()).isEqualTo("EMPTY");
    }
}
