package com.picsou.service.sso;

import com.picsou.config.SsoProperties;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.SetupAuditService;
import com.picsou.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Mirrors membership of the configured IdP admin group onto {@link UserRole} at SSO login. Rows are
 * always saved through the managed instance loaded here, never the caller's possibly detached
 * {@code user}, which could overwrite a concurrent {@code tokenVersion} bump. The role is read from
 * the database on every request, so no token version change is needed. The last admin is never
 * demoted; the admin rows are locked first so two concurrent demotions cannot both succeed.
 */
@Service
public class SsoRoleSyncService {

    private static final Logger log = LoggerFactory.getLogger(SsoRoleSyncService.class);

    private final AppUserRepository appUserRepository;
    private final SetupAuditService setupAuditService;
    private final SsoProperties ssoProperties;

    public SsoRoleSyncService(AppUserRepository appUserRepository,
                              SetupAuditService setupAuditService,
                              SsoProperties ssoProperties) {
        this.appUserRepository = appUserRepository;
        this.setupAuditService = setupAuditService;
        this.ssoProperties = ssoProperties;
    }

    /** Returns the role after the sync and mirrors it onto {@code user} without saving that instance. */
    @Transactional
    public UserRole sync(AppUser user, Optional<Set<String>> groups, HttpServletRequest request) {
        if (!ssoProperties.adminGroupManaged()) {
            return user.getRole();
        }
        if (groups.isEmpty()) {
            log.warn("sso.groups-claim-missing user={}", LogSanitizer.safe(user.getUsername()));
        }
        boolean inAdminGroup = groups.orElse(Set.of()).contains(ssoProperties.adminGroup());
        UserRole current = user.getRole();
        if (inAdminGroup && current == UserRole.MEMBER) {
            promote(user, request);
        } else if (!inAdminGroup && current == UserRole.ADMIN) {
            demote(user, request);
        }
        return user.getRole();
    }

    private void promote(AppUser user, HttpServletRequest request) {
        AppUser managed = appUserRepository.findById(user.getId()).orElseThrow();
        managed.setRole(UserRole.ADMIN);
        appUserRepository.save(managed);
        user.setRole(UserRole.ADMIN);
        setupAuditService.record("sso.role.promoted", user.getUsername(), request, null);
    }

    private void demote(AppUser user, HttpServletRequest request) {
        List<AppUser> admins = appUserRepository.findAllByRoleForUpdate(UserRole.ADMIN);
        Optional<AppUser> managed = admins.stream()
            .filter(admin -> admin.getId().equals(user.getId()))
            .findFirst();
        if (managed.isEmpty()) {
            return;
        }
        if (admins.size() == 1) {
            log.warn("sso.role.demotion-skipped-last-admin user={}", LogSanitizer.safe(user.getUsername()));
            setupAuditService.record("sso.role.demotion-skipped-last-admin", user.getUsername(), request, null);
            return;
        }
        AppUser row = managed.get();
        row.setRole(UserRole.MEMBER);
        appUserRepository.save(row);
        user.setRole(UserRole.MEMBER);
        setupAuditService.record("sso.role.demoted", user.getUsername(), request, null);
    }
}
