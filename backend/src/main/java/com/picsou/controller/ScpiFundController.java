package com.picsou.controller;

import com.picsou.dto.ScpiFundResponse;
import com.picsou.model.ScpiProvider;
import com.picsou.service.ScpiFundService;
import com.picsou.service.UserContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The SCPI funds a CORUM or Sofidy sync has seen for the current member, with the
 * per-fund outcome of the latest sync, and the "ignore this fund" switch.
 */
@RestController
@RequestMapping("/api/scpi/funds")
public class ScpiFundController {
    private final ScpiFundService service;
    private final UserContext userContext;

    public ScpiFundController(ScpiFundService service, UserContext userContext) {
        this.service = service;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ScpiFundResponse> list() {
        return service.list(userContext.currentMemberId());
    }

    /** Unlinks the fund from its account and keeps every later sync away from it. The account stays. */
    @PostMapping("/{provider}/{fundCode}/ignore")
    public ResponseEntity<Void> ignore(@PathVariable ScpiProvider provider, @PathVariable String fundCode) {
        service.ignore(userContext.currentMemberId(), provider, fundCode);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{provider}/{fundCode}/restore")
    public ResponseEntity<Void> restore(@PathVariable ScpiProvider provider, @PathVariable String fundCode) {
        service.restore(userContext.currentMemberId(), provider, fundCode);
        return ResponseEntity.noContent().build();
    }
}
