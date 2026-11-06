package com.picsou.config.spaslice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package, so {@code @WebMvcTest}
 * does not find {@link com.picsou.PicsouApplication} and its JPA auditing. See
 * {@code HstsSliceTestApplication} for the full reasoning, including why it must not exclude
 * auto-configurations.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class SpaSliceTestApplication {
}
