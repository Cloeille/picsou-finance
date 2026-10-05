package com.picsou.telemetry.slice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package, so
 * {@code @WebMvcTest} does not pick up {@link com.picsou.PicsouApplication} and its JPA auditing.
 * Same rationale as {@code com.picsou.config.csrfslice.CsrfSliceTestApplication}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TelemetrySliceTestApplication {
}
