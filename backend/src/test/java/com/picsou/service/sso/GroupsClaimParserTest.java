package com.picsou.service.sso;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GroupsClaimParserTest {

    @Test
    void parsesStringCollection() {
        assertThat(GroupsClaimParser.parse(List.of("a", "b")))
            .contains(Set.of("a", "b"));
    }

    @Test
    void parsesSingleString() {
        assertThat(GroupsClaimParser.parse("picsou-admin"))
            .contains(Set.of("picsou-admin"));
    }

    @Test
    void parsesMapKeysAsGroups() {
        assertThat(GroupsClaimParser.parse(Map.of("picsou-admin", Map.of("123", "org.example"))))
            .contains(Set.of("picsou-admin"));
    }

    @Test
    void rejectsNull() {
        assertThat(GroupsClaimParser.parse(null)).isEmpty();
    }

    @Test
    void rejectsNumber() {
        assertThat(GroupsClaimParser.parse(42)).isEmpty();
    }

    @Test
    void rejectsMixedCollection() {
        assertThat(GroupsClaimParser.parse(List.of("a", 1))).isEmpty();
    }

    @Test
    void acceptsEmptyCollection() {
        assertThat(GroupsClaimParser.parse(List.of())).contains(Set.of());
    }

    @Test
    void rejectsMapWithNonStringKey() {
        assertThat(GroupsClaimParser.parse(Map.of(1, "a"))).isEmpty();
    }
}
