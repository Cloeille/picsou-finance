package com.picsou.controller.instrumentlogoslice;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package, for the reason
 * {@code com.picsou.config.hstsslice.HstsSliceTestApplication} documents: without it the slice
 * would find {@code PicsouApplication} and its JPA auditing, which a web slice cannot satisfy.
 * Same warning applies: do not add autoconfiguration excludes here.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class InstrumentLogoSliceTestApplication {
}
