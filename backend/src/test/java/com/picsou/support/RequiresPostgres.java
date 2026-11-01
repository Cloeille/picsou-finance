package com.picsou.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that starts its own PostgreSQL container: enables the Testcontainers
 * extension and skips the class when no Docker daemon is reachable (a failure instead when
 * {@code PICSOU_REQUIRE_DOCKER_TESTS=true}, see {@link PostgresContainers#dockerAvailable()}).
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Testcontainers
@EnabledIf("com.picsou.support.PostgresContainers#dockerAvailable")
public @interface RequiresPostgres {}
