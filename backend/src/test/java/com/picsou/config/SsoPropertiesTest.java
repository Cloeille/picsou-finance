package com.picsou.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SsoPropertiesTest {

    private static final String ISSUER = "https://idp.example.com/realm";

    @Mock private ConditionContext conditionContext;
    @Mock private AnnotatedTypeMetadata metadata;

    private final Logger logger = (Logger) LoggerFactory.getLogger(SsoProperties.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> snapshot() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private static SsoProperties props(String issuer, String clientId) {
        SsoProperties p = new SsoProperties();
        p.setIssuerUri(issuer);
        p.setClientId(clientId);
        return p;
    }

    private boolean conditionMatches(MockEnvironment env) {
        lenient().when(conditionContext.getEnvironment()).thenReturn(env);
        return new SsoEnabledCondition().matches(conditionContext, metadata);
    }

    @Test
    void enabled_onlyWithIssuerAndClientId() {
        assertThat(props(ISSUER, "picsou").enabled()).isTrue();
        assertThat(props(ISSUER, "").enabled()).isFalse();
        assertThat(props("", "picsou").enabled()).isFalse();
        assertThat(props(null, null).enabled()).isFalse();
    }

    @Test
    void evaluate_blankIssuer_disabledWithoutProblem() {
        SsoProperties.Evaluation e = SsoProperties.evaluate("  ", "picsou", "24h");

        assertThat(e.enabled()).isFalse();
        assertThat(e.problem()).isNull();
    }

    @Test
    void evaluate_issuerNotAbsoluteHttpUri_isProblem() {
        for (String bad : List.of("not-a-url", "/relative", "ftp://idp.example.com", "https://", "http://bad host")) {
            SsoProperties.Evaluation e = SsoProperties.evaluate(bad, "picsou", "24h");
            assertThat(e.enabled()).as(bad).isFalse();
            assertThat(e.problem()).as(bad).isNotBlank();
        }
    }

    @Test
    void evaluate_issuerWithoutClientId_isProblem() {
        SsoProperties.Evaluation e = SsoProperties.evaluate(ISSUER, "  ", "24h");

        assertThat(e.enabled()).isFalse();
        assertThat(e.problem()).isNotBlank();
    }

    @Test
    void evaluate_unparseableOrNonPositiveDuration_isProblem() {
        for (String bad : List.of("abc", "0s", "-5m")) {
            SsoProperties.Evaluation e = SsoProperties.evaluate(ISSUER, "picsou", bad);
            assertThat(e.enabled()).as(bad).isFalse();
            assertThat(e.problem()).as(bad).isNotBlank();
        }
    }

    @Test
    void revalidateAfter_parsesHoursAndMinutes() {
        SsoProperties p = props(ISSUER, "picsou");

        p.setSessionRevalidateAfter("24h");
        assertThat(p.revalidateAfter()).isEqualTo(Duration.ofHours(24));
        p.setSessionRevalidateAfter("90m");
        assertThat(p.revalidateAfter()).isEqualTo(Duration.ofMinutes(90));
        assertThat(p.enabled()).isTrue();
    }

    @Test
    void revalidateAfter_unparseable_disablesWithoutThrowing() {
        SsoProperties p = props(ISSUER, "picsou");
        p.setSessionRevalidateAfter("abc");

        assertThat(p.enabled()).isFalse();
        assertThat(p.revalidateAfter()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void effectiveScopes_openidFirstAndExactlyOnce() {
        SsoProperties p = props(ISSUER, "picsou");

        p.setScopes("profile, email ,openid,,profile");
        assertThat(p.effectiveScopes()).containsExactly("openid", "profile", "email");
        p.setScopes("");
        assertThat(p.effectiveScopes()).containsExactly("openid");
    }

    @Test
    void groupsClaim_keptLiteral_blankFallsBackToGroups() {
        SsoProperties p = props(ISSUER, "picsou");

        p.setGroupsClaim("urn:zitadel:iam:org:project:roles");
        assertThat(p.groupsClaim()).isEqualTo("urn:zitadel:iam:org:project:roles");
        p.setGroupsClaim("  ");
        assertThat(p.groupsClaim()).isEqualTo("groups");
    }

    @Test
    void adminGroup_blankMeansNotManaged() {
        SsoProperties p = props(ISSUER, "picsou");

        p.setAdminGroup("  ");
        assertThat(p.adminGroupManaged()).isFalse();
        assertThat(p.adminGroupConfigured()).isFalse();
        p.setAdminGroup(" picsou-admin ");
        assertThat(p.adminGroupManaged()).isTrue();
        assertThat(p.adminGroup()).isEqualTo("picsou-admin");
    }

    @Test
    void adminGroupConfigured_trueWithMalformedIssuerWhileDisabled() {
        SsoProperties p = props("not-a-url", "picsou");
        p.setAdminGroup("picsou-admin");

        assertThat(p.enabled()).isFalse();
        assertThat(p.adminGroupManaged()).isFalse();
        assertThat(p.adminGroupConfigured()).isTrue();
    }

    @Test
    void idpLogout_lenientTruthyParsing() {
        SsoProperties p = props(ISSUER, "picsou");

        for (String truthy : List.of("true", " TRUE ", "1", "yes", "On")) {
            p.setIdpLogout(truthy);
            assertThat(p.idpLogout()).as(truthy).isTrue();
        }
        for (String falsy : List.of("false", "", "nope", "0")) {
            p.setIdpLogout(falsy);
            assertThat(p.idpLogout()).as(falsy).isFalse();
        }
        p.setIdpLogout(null);
        assertThat(p.idpLogout()).isFalse();
    }

    @Test
    void providerName_trimmedCappedAndDefaulted() {
        SsoProperties p = props(ISSUER, "picsou");

        p.setProviderName("  Zitadel ");
        assertThat(p.providerName()).isEqualTo("Zitadel");
        p.setProviderName("x".repeat(60));
        assertThat(p.providerName()).hasSize(40);
        p.setProviderName("   ");
        assertThat(p.providerName()).isEqualTo("SSO");
    }

    @Test
    void clientSecret_blankMeansPublicClient() {
        SsoProperties p = props(ISSUER, " picsou ");

        assertThat(p.clientId()).isEqualTo("picsou");
        p.setClientSecret("   ");
        assertThat(p.clientSecret()).isEmpty();
        assertThat(p.publicClient()).isTrue();
        p.setClientSecret(" s3cret ");
        assertThat(p.clientSecret()).isEqualTo("s3cret");
        assertThat(p.publicClient()).isFalse();
    }

    @Test
    void issuerUri_trimmedOtherwiseLiteral() {
        assertThat(props(" https://idp.example.com/realm/ ", "picsou").issuerUri())
            .isEqualTo("https://idp.example.com/realm/");
    }

    @Test
    void condition_matchesExactlyWhenEvaluateEnables() {
        assertThat(conditionMatches(new MockEnvironment()
            .withProperty("app.sso.issuer-uri", ISSUER)
            .withProperty("app.sso.client-id", "picsou"))).isTrue();
        assertThat(conditionMatches(new MockEnvironment()
            .withProperty("app.sso.issuer-uri", ISSUER)
            .withProperty("app.sso.client-id", "picsou")
            .withProperty("app.sso.session-revalidate-after", "90m"))).isTrue();
        assertThat(conditionMatches(new MockEnvironment()
            .withProperty("app.sso.issuer-uri", ISSUER)
            .withProperty("app.sso.client-id", "picsou")
            .withProperty("app.sso.session-revalidate-after", "abc"))).isFalse();
        assertThat(conditionMatches(new MockEnvironment()
            .withProperty("app.sso.issuer-uri", "not-a-url")
            .withProperty("app.sso.client-id", "picsou"))).isFalse();
        assertThat(conditionMatches(new MockEnvironment()
            .withProperty("app.sso.issuer-uri", ISSUER))).isFalse();
        assertThat(conditionMatches(new MockEnvironment())).isFalse();
    }

    @Test
    void afterPropertiesSet_invalidConfig_logsErrorAndDoesNotThrow() throws Exception {
        props("not-a-url", "picsou").afterPropertiesSet();

        List<ILoggingEvent> events = snapshot();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(0).getFormattedMessage())
            .startsWith("SSO configuration invalid (").endsWith("); SSO is disabled");
    }

    @Test
    void afterPropertiesSet_enabled_logsInfoSummary() throws Exception {
        SsoProperties p = props(ISSUER, "picsou");
        p.setAdminGroup("picsou-admin");
        p.afterPropertiesSet();

        List<ILoggingEvent> events = snapshot();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(0).getFormattedMessage())
            .isEqualTo("SSO enabled: issuer=" + ISSUER + " provider=SSO adminGroupManaged=true");
    }

    @ParameterizedTest
    @CsvSource({
        "http://idp.example.com/realm, true",
        "http://192.168.1.10:8080, true",
        "http://128.0.0.1, true",
        "http://[2001:db8::1], true",
        "http://127.0.0.1:8080/realm, false",
        "http://127.1.2.3, false",
        "http://localhost:9000, false",
        "http://LOCALHOST, false",
        "http://[::1]:9000, false",
        "https://idp.example.com/realm, false",
        "https://192.168.1.10, false",
        "not-a-url, false"
    })
    void isInsecureIssuer_flagsPlainHttpOffLoopbackOnly(String issuer, boolean insecure) {
        assertThat(SsoProperties.isInsecureIssuer(issuer)).isEqualTo(insecure);
    }

    @Test
    void afterPropertiesSet_plainHttpIssuerOffLoopback_warnsOnceAtStartup() throws Exception {
        SsoProperties p = props("http://idp.example.com/realm", "picsou");
        p.afterPropertiesSet();
        p.enabled();
        p.enabled();

        List<ILoggingEvent> warnings = snapshot().stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
            .contains("http://idp.example.com/realm", "plain http", "client secret");
    }

    @Test
    void afterPropertiesSet_loopbackHttpIssuer_doesNotWarn() throws Exception {
        props("http://127.0.0.1:9000", "picsou").afterPropertiesSet();

        assertThat(snapshot()).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void afterPropertiesSet_notConfigured_logsNothing() throws Exception {
        props("", "").afterPropertiesSet();

        assertThat(snapshot()).isEmpty();
    }
}
