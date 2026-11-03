package com.picsou.service;

import com.picsou.repository.AppUserRepository;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
public class UsernameDeriver {

    private final AppUserRepository userRepository;

    public UsernameDeriver(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Derives a readable, login-safe username from a member's display name
     * (e.g. "Jean Dupont" -> "jean.dupont"), replacing the legacy "member_<id>"
     * scheme. Accents are stripped, non-alphanumeric runs collapse to a dot, and
     * a numeric suffix (".2", ".3", ...) guarantees uniqueness on collision.
     */
    public String derive(String displayName) {
        String base = Normalizer
            .normalize(displayName == null ? "" : displayName, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")        // strip accent marks
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", ".")     // any separator run -> single dot
            .replaceAll("^\\.+|\\.+$", "");    // trim leading/trailing dots
        if (base.isBlank()) {
            base = "user";
        }
        if (base.length() > 45) {
            base = base.substring(0, 45);      // leave room for a numeric suffix
        }
        String candidate = base;
        int n = 2;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + "." + n++;
        }
        return candidate;
    }
}
