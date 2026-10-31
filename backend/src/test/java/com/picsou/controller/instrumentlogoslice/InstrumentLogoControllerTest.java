package com.picsou.controller.instrumentlogoslice;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtTokenAuthenticator;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecurityConfig;
import com.picsou.config.SetupFilter;
import com.picsou.controller.InstrumentLogoController;
import com.picsou.mcp.AccessKeyService;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.InstrumentLogoService;
import com.picsou.service.InstrumentLogoService.ServedImage;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint the browser loads a share's mark from, through the real {@link SecurityConfig}:
 * the bytes must never be served to an anonymous caller, and the cache headers are what keep a
 * holdings page from re-downloading every mark on each render.
 */
@WebMvcTest(controllers = InstrumentLogoController.class)
@Import({SecurityConfig.class, InstrumentLogoController.class})
class InstrumentLogoControllerTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final Instant FETCHED = Instant.ofEpochSecond(1759400000);

    @MockitoBean InstrumentLogoService logoService;

    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean JwtTokenAuthenticator jwtTokenAuthenticator;
    @MockitoBean AppUserRepository appUserRepository;
    @MockitoBean SetupFilter setupFilter;
    @MockitoBean PersistentSessionService persistentSessionService;
    @MockitoBean AuthCookieWriter authCookieWriter;
    @MockitoBean MfaService mfaService;
    @MockitoBean AccessKeyService accessKeyService;
    @MockitoBean AppSettingRepository appSettingRepository;
    @MockitoBean @Qualifier("mcpKeyBuckets") Map<Long, Bucket> mcpKeyBuckets;

    @Autowired MockMvc mvc;

    @BeforeEach
    void passThroughSetupFilter() throws Exception {
        doAnswer(inv -> {
            ServletRequest req = inv.getArgument(0);
            ServletResponse res = inv.getArgument(1);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(req, res);
            return null;
        }).when(setupFilter).doFilter(any(), any(), any());
    }

    @Test
    void refusesAnAnonymousCaller_withoutReadingTheStore() throws Exception {
        mvc.perform(get("/api/instrument-logos/AAPL"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(logoService);
    }

    @Test
    void servesTheStoredBytes_withPrivateLongLivedCaching_andAnEtag() throws Exception {
        when(logoService.image("AAPL", false))
            .thenReturn(Optional.of(new ServedImage(PNG, "image/png", FETCHED, false)));

        mvc.perform(get("/api/instrument-logos/AAPL").param("v", "1759400000").with(user("member")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(PNG))
            .andExpect(header().string("Cache-Control", "max-age=2592000, private"))
            .andExpect(header().string("ETag", "\"1759400000-light\""));
    }

    @Test
    void answersARevalidationWithA304() throws Exception {
        when(logoService.image("AAPL", false))
            .thenReturn(Optional.of(new ServedImage(PNG, "image/png", FETCHED, false)));

        mvc.perform(get("/api/instrument-logos/AAPL").header("If-None-Match", "\"1759400000-light\"").with(user("member")))
            .andExpect(status().isNotModified())
            .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void asksForTheDarkVariantOnlyWhenRequested() throws Exception {
        when(logoService.image("MC.PA", true))
            .thenReturn(Optional.of(new ServedImage(PNG, "image/png", FETCHED, true)));

        mvc.perform(get("/api/instrument-logos/MC.PA").param("variant", "dark").with(user("member")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1759400000-dark\""));

        verify(logoService).image("MC.PA", true);
    }

    @Test
    void answers404WhenNothingIsStored() throws Exception {
        when(logoService.image(anyString(), anyBoolean())).thenReturn(Optional.empty());

        mvc.perform(get("/api/instrument-logos/NOLOGO").with(user("member")))
            .andExpect(status().isNotFound());
    }
}
