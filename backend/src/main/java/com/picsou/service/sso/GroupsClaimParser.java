package com.picsou.service.sso;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class GroupsClaimParser {

    private GroupsClaimParser() {}

    public static Optional<Set<String>> parse(Object claimValue) {
        if (claimValue instanceof Collection<?> collection) {
            if (collection.stream().allMatch(String.class::isInstance)) {
                return Optional.of(collection.stream()
                    .map(String.class::cast)
                    .collect(Collectors.toSet()));
            }
            return Optional.empty();
        }
        if (claimValue instanceof String group) {
            return Optional.of(Set.of(group));
        }
        if (claimValue instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance)) {
            return Optional.of(map.keySet().stream()
                .map(String.class::cast)
                .collect(Collectors.toSet()));
        }
        return Optional.empty();
    }
}
