package com.picsou.config.csrfslice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package, so
 * {@code @WebMvcTest} does not pick up {@link com.picsou.PicsouApplication} and its JPA auditing.
 * Same rationale and same "no explicit excludes" rule as
 * {@code com.picsou.config.hstsslice.HstsSliceTestApplication}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class CsrfSliceTestApplication {
}
