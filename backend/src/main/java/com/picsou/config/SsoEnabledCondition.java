package com.picsou.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches exactly when {@link SsoProperties#evaluate} enables SSO, reading the same
 * {@code app.sso.*} keys straight from the environment (conditions run before property binding).
 */
public class SsoEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        return SsoProperties.evaluate(
            env.getProperty("app.sso.issuer-uri"),
            env.getProperty("app.sso.client-id"),
            env.getProperty("app.sso.session-revalidate-after", SsoProperties.DEFAULT_REVALIDATE_AFTER)
        ).enabled();
    }
}
