package com.picsou.config;

import com.picsou.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

class LoggingCorsProcessor extends DefaultCorsProcessor {

    private static final Logger log = LoggerFactory.getLogger(LoggingCorsProcessor.class);

    @Override
    public boolean processRequest(CorsConfiguration config, HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean allowed = super.processRequest(config, request, response);
        if (!allowed) {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            log.warn("CORS rejected — origin: '{}' | allowed patterns: {}",
                LogSanitizer.safe(origin),
                config != null ? config.getAllowedOriginPatterns() : "none");
        }
        return allowed;
    }
}
