package com.picsou.service;

import com.picsou.repository.AppUserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsernameDeriverTest {

    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final UsernameDeriver usernameDeriver = new UsernameDeriver(userRepository);

    @Test
    void derive_stripsAccents() {
        assertThat(usernameDeriver.derive("Élodie")).isEqualTo("elodie");
    }

    @Test
    void derive_collapsesSeparators() {
        assertThat(usernameDeriver.derive("Jean --- Dupont")).isEqualTo("jean.dupont");
    }

    @Test
    void derive_usesUserWhenBaseIsBlank() {
        assertThat(usernameDeriver.derive("!!!")).isEqualTo("user");
    }

    @Test
    void derive_appendsCollisionSuffix() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThat(usernameDeriver.derive("Alice")).isEqualTo("alice.2");
    }

    @Test
    void derive_capsBaseAtFortyFiveCharacters() {
        String base = "a".repeat(50);

        assertThat(usernameDeriver.derive(base)).isEqualTo("a".repeat(45));
    }
}
