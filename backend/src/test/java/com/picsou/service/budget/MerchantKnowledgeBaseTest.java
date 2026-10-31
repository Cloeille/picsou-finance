package com.picsou.service.budget;

import com.picsou.model.MerchantAlias;
import com.picsou.model.MerchantBrand;
import com.picsou.repository.MerchantAliasRepository;
import com.picsou.repository.MerchantBrandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantKnowledgeBaseTest {

    @Mock MerchantBrandRepository brandRepository;
    @Mock MerchantAliasRepository aliasRepository;

    private MerchantKnowledgeBase kb;

    private static MerchantBrand brand(long id, String slug, String categorySlug) {
        return MerchantBrand.builder()
            .id(id).slug(slug).displayName(slug).defaultCategorySlug(categorySlug).build();
    }

    private static MerchantAlias alias(long brandId, String pattern, MerchantAlias.MatchType type) {
        return MerchantAlias.builder().brandId(brandId).pattern(pattern).matchType(type).build();
    }

    @BeforeEach
    void load() {
        when(brandRepository.findAll()).thenReturn(List.of(
            brand(1, "carrefour", "courses"),
            brand(2, "uber", "transport"),
            brand(3, "uber-eats", "restaurants"),
            brand(4, "leclerc", "courses")
        ));
        when(aliasRepository.findAll()).thenReturn(List.of(
            alias(1, "carrefour", MerchantAlias.MatchType.WORD),
            alias(2, "uber", MerchantAlias.MatchType.WORD),
            alias(3, "uber eats", MerchantAlias.MatchType.PHRASE),
            alias(4, "leclerc", MerchantAlias.MatchType.WORD)
        ));
        kb = new MerchantKnowledgeBase(brandRepository, aliasRepository);
        kb.reload();
    }

    @Test
    void matchesSingleWordAgainstAnyToken() {
        assertThat(kb.match("carrefour market paris 12")).map(MerchantKnowledgeBase.Brand::slug)
            .contains("carrefour");
    }

    @Test
    void splitsOnDotsSoDottedBrandsTokenize() {
        // "e.leclerc" must yield the {e, leclerc} tokens, matching the "leclerc" word alias.
        assertThat(kb.match("e.leclerc")).map(MerchantKnowledgeBase.Brand::slug).contains("leclerc");
    }

    @Test
    void phraseAliasOutranksParentWordAlias() {
        // "uber eats" (PHRASE → restaurants) must win over the bare "uber" (WORD → transport).
        assertThat(kb.match("uber eats paris")).map(MerchantKnowledgeBase.Brand::slug).contains("uber-eats");
        assertThat(kb.match("uber trip 12 34")).map(MerchantKnowledgeBase.Brand::slug).contains("uber");
    }

    @Test
    void respectsWordBoundaries() {
        // A token that merely contains a brand word must NOT match ("ubered" ≠ "uber").
        assertThat(kb.match("ubered services")).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownOrBlank() {
        assertThat(kb.match("boulangerie dupont")).isEmpty();
        assertThat(kb.match("")).isEmpty();
        assertThat(kb.match(null)).isEmpty();
    }

    @Test
    void findByIdResolvesLoadedBrands() {
        assertThat(kb.findById(2L)).map(MerchantKnowledgeBase.Brand::slug).contains("uber");
        assertThat(kb.findById(999L)).isEmpty();
        assertThat(kb.findById(null)).isEmpty();
        assertThat(kb.brandCount()).isEqualTo(4);
    }
}
