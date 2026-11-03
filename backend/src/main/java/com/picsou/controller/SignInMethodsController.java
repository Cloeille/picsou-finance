package com.picsou.controller;

import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.service.SignInMethodsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SignInMethodsController {

    private final SignInMethodsService signInMethodsService;

    public SignInMethodsController(SignInMethodsService signInMethodsService) {
        this.signInMethodsService = signInMethodsService;
    }

    @GetMapping("/sso")
    public SsoInfoResponse sso() {
        return signInMethodsService.ssoInfo();
    }

    @GetMapping("/sign-in-methods")
    public SignInMethodsResponse signInMethods(@AuthenticationPrincipal AppUser user) {
        return signInMethodsService.signInMethods(user);
    }
}
