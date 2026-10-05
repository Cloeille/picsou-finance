package com.picsou.controller;

import com.picsou.config.ClientIp;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.HomeBankImportDtos.Preview;
import com.picsou.dto.HomeBankImportDtos.Request;
import com.picsou.dto.HomeBankImportDtos.Result;
import com.picsou.service.HomeBankImportService;
import com.picsou.service.UserContext;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/homebank/import")
public class HomeBankImportController {
    private final HomeBankImportService service;
    private final UserContext userContext;
    private final Map<String,Bucket> syncBuckets;
    public HomeBankImportController(HomeBankImportService service, UserContext userContext,
            @Qualifier("syncBuckets") Map<String,Bucket> syncBuckets) {
        this.service=service;this.userContext=userContext;this.syncBuckets=syncBuckets;
    }
    @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file,
            @RequestParam(value="password",required=false) String password,
            @RequestParam(value="currency",required=false) String currency,HttpServletRequest request) {
        if(!checkRateLimit(request))return tooManyRequests();
        Preview result=currency == null ? service.preview(file,password,userContext.currentMemberId())
                : service.preview(file,password,currency,userContext.currentMemberId());
        return ResponseEntity.ok(result);
    }
    public ResponseEntity<?> preview(MultipartFile file,String password,HttpServletRequest request) {
        return preview(file,password,null,request);
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@Valid @RequestBody Request body,HttpServletRequest request) {
        if(!checkRateLimit(request))return tooManyRequests();
        Result result=service.executeImport(body,userContext.currentMemberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
    private boolean checkRateLimit(HttpServletRequest request){String ip=ClientIp.resolve(request);return syncBuckets.computeIfAbsent(ip,k->RateLimitConfig.createSyncBucket()).tryConsume(1);}
    private static ResponseEntity<ProblemDetail> tooManyRequests(){ProblemDetail detail=ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);detail.setDetail("Too many import requests. Please wait a moment.");return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);}
}
