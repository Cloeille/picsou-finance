package com.picsou.controller;

import com.picsou.dto.TelemetryConfigResponse;
import com.picsou.telemetry.TelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

import static com.picsou.telemetry.TelemetryService.MAX_ENVELOPE_BYTES;

/**
 * Opt-in telemetry endpoints for the SPA. Open to any authenticated user (the catch-all rule in
 * {@code SecurityConfig}); only the consent toggle is admin-only ({@code AdminController}).
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetry;

    public TelemetryController(TelemetryService telemetry) {
        this.telemetry = telemetry;
    }

    @GetMapping("/config")
    public ResponseEntity<TelemetryConfigResponse> config() {
        return ResponseEntity.ok(telemetry.config());
    }

    /**
     * Sentry envelope tunnel. The body is read by hand (it is {@code text/plain} from the browser
     * SDK, {@code application/x-sentry-envelope} otherwise) and capped at 200 KB. Always 204 unless
     * the body is too large.
     */
    @PostMapping("/tunnel")
    public ResponseEntity<?> tunnel(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_ENVELOPE_BYTES) {
            return tooLarge();
        }
        if (!telemetry.isEnabled()) {
            return ResponseEntity.noContent().build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_ENVELOPE_BYTES + 1);
        }
        if (body.length > MAX_ENVELOPE_BYTES) {
            return tooLarge();
        }
        telemetry.tunnel(body);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ProblemDetail> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Envelope too large"));
    }
}
