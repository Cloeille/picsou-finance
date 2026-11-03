package com.picsou.controller;

import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.service.SignInMethodsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignInMethodsControllerTest {

    @Mock private SignInMethodsService service;

    @Test
    void sso_returnsTheServiceInfo() {
        SsoInfoResponse info = new SsoInfoResponse(false, "SSO");
        when(service.ssoInfo()).thenReturn(info);

        assertThat(new SignInMethodsController(service).sso()).isSameAs(info);
    }

    @Test
    void signInMethods_isBuiltForThePrincipal() {
        AppUser user = AppUser.builder().id(7L).username("chloe").build();
        SignInMethodsResponse response =
            new SignInMethodsResponse(true, false, List.of(), false, "SSO", false);
        when(service.signInMethods(user)).thenReturn(response);

        assertThat(new SignInMethodsController(service).signInMethods(user)).isSameAs(response);
    }
}
