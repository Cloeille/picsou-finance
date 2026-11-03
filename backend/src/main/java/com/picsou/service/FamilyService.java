package com.picsou.service;

import com.picsou.dto.FamilyMemberRequest;
import com.picsou.dto.FamilyMemberResponse;
import com.picsou.dto.SharingSettingsRequest;
import com.picsou.dto.SharingSettingsResponse;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class FamilyService {

    private final FamilyMemberRepository memberRepository;
    private final AppUserRepository userRepository;
    private final UserMfaRepository userMfaRepository;
    private final UserIdentityRepository identityRepository;
    private final SharingSettingsRepository sharingSettingsRepository;
    private final SharedResourceRepository sharedResourceRepository;
    private final AccountRepository accountRepository;
    private final GoalRepository goalRepository;
    private final PasswordEncoder passwordEncoder;
    private final UsernameDeriver usernameDeriver;

    public FamilyService(
        FamilyMemberRepository memberRepository,
        AppUserRepository userRepository,
        UserMfaRepository userMfaRepository,
        UserIdentityRepository identityRepository,
        SharingSettingsRepository sharingSettingsRepository,
        SharedResourceRepository sharedResourceRepository,
        AccountRepository accountRepository,
        GoalRepository goalRepository,
        PasswordEncoder passwordEncoder,
        UsernameDeriver usernameDeriver
    ) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.userMfaRepository = userMfaRepository;
        this.identityRepository = identityRepository;
        this.sharingSettingsRepository = sharingSettingsRepository;
        this.sharedResourceRepository = sharedResourceRepository;
        this.accountRepository = accountRepository;
        this.goalRepository = goalRepository;
        this.passwordEncoder = passwordEncoder;
        this.usernameDeriver = usernameDeriver;
    }

    public List<FamilyMemberResponse> listMembers() {
        List<FamilyMember> members = memberRepository.findAllByOrderByCreatedAtAsc();
        List<AppUser> users = members.stream()
            .map(m -> userRepository.findByMemberId(m.getId()).orElse(null))
            .toList();
        Set<Long> linkedUserIds = identityRepository.findUserIdsWithIdentity(
            users.stream().filter(Objects::nonNull).map(AppUser::getId).toList());
        List<FamilyMemberResponse> responses = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            AppUser user = users.get(i);
            boolean mfaEnabled = user != null && userMfaRepository.findByUserId(user.getId())
                .map(UserMfa::isEnabled)
                .orElse(false);
            boolean ssoLinked = user != null && linkedUserIds.contains(user.getId());
            responses.add(FamilyMemberResponse.from(members.get(i), user, mfaEnabled, ssoLinked));
        }
        return responses;
    }

    @Transactional
    public FamilyMemberResponse createManagedProfile(FamilyMemberRequest req) {
        FamilyMember member = FamilyMember.builder()
            .displayName(req.displayName())
            .avatarColor(req.avatarColor() != null ? req.avatarColor() : "#6366f1")
            .managed(true)
            .build();
        member = memberRepository.save(member);
        return FamilyMemberResponse.from(member, null, false, false);
    }

    @Transactional
    public FamilyMemberResponse updateDisplayName(Long id, String displayName) {
        FamilyMember member = memberRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        member.setDisplayName(displayName);
        member = memberRepository.save(member);
        AppUser user = userRepository.findByMemberId(id).orElse(null);
        boolean mfaEnabled = user != null && userMfaRepository.findByUserId(user.getId())
            .map(UserMfa::isEnabled)
            .orElse(false);
        boolean ssoLinked = user != null && identityRepository.countByUserId(user.getId()) > 0;
        return FamilyMemberResponse.from(member, user, mfaEnabled, ssoLinked);
    }

    /**
     * Deletes a family member together with their login and all owned data
     * (accounts, goals, requisitions, bank sessions, wallets, debts…).
     *
     * <p>An activated member is no longer protected — the admin who runs the instance
     * may remove anyone. Two guards remain to keep the instance usable:
     * <ul>
     *   <li>an admin cannot delete their own member (would lock themselves out);</li>
     *   <li>the last remaining administrator cannot be deleted (no one left to administer).</li>
     * </ul>
     *
     * <p><b>Deletion order matters.</b> The member's {@link AppUser} is loaded into the
     * persistence context above (for the last-admin guard). {@code AppUser.member} is a
     * non-nullable {@code @OneToOne}, so deleting the member while that managed user still
     * references it makes Hibernate throw {@code TransientObjectException} at flush —
     * before any SQL runs, so the DB {@code ON DELETE CASCADE} never gets a chance. We
     * therefore delete the loaded {@code AppUser} first; the member delete then cascades
     * the remaining (unloaded) owned rows at the database level.
     *
     * @param requesterMemberId the member id of the admin performing the deletion
     */
    @Transactional
    public void deleteMember(Long id, Long requesterMemberId) {
        FamilyMember member = memberRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (id.equals(requesterMemberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete your own account");
        }
        AppUser user = userRepository.findByMemberId(id).orElse(null);
        if (user != null && user.getRole() == UserRole.ADMIN
            && userRepository.findAllByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the last administrator");
        }
        if (user != null) {
            // Remove the only managed entity referencing the member before deleting it,
            // otherwise Hibernate fails the flush with TransientObjectException.
            userRepository.delete(user);
        }
        memberRepository.delete(member);
    }

    @Transactional
    public String resetPasswordToken(Long memberId) {
        memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        AppUser user = userRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member has no login to reset"));

        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        user.setActivationToken(token);
        user.setActivationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS));
        userRepository.save(user);

        return token;
    }

    @Transactional
    public String generateActivationToken(Long memberId) {
        FamilyMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        AppUser existingUser = userRepository.findByMemberId(memberId).orElse(null);
        if (existingUser != null && existingUser.isActivated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This account already has an active login.");
        }

        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        if (existingUser != null) {
            existingUser.setActivationToken(token);
            existingUser.setActivationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS));
            userRepository.save(existingUser);
        } else {
            AppUser user = AppUser.builder()
                .username(usernameDeriver.derive(member.getDisplayName()))
                .passwordHash("")
                .member(member)
                .role(UserRole.MEMBER)
                .activated(false)
                .activationToken(token)
                .activationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS))
                .acknowledgedWarning(false)
                .build();
            userRepository.save(user);
        }

        return token;
    }

    public SharingSettingsResponse getSharingSettings(Long memberId, String resourceType) {
        SharingSettings settings = sharingSettingsRepository
            .findByMemberIdAndResourceType(memberId, resourceType)
            .orElseGet(() -> new SharingSettings(null, null, resourceType, SharingLevel.NONE));

        List<Long> sharedIds = List.of();
        if (settings.getSharingLevel() == SharingLevel.MANUAL) {
            sharedIds = sharedResourceRepository
                .findAllByOwnerMemberIdAndResourceType(memberId, resourceType).stream()
                .map(SharedResource::getResourceId)
                .toList();
        } else if (settings.getSharingLevel() == SharingLevel.ALL) {
            sharedIds = List.of(-1L);
        }

        return new SharingSettingsResponse(resourceType, settings.getSharingLevel(), sharedIds);
    }

    @Transactional
    public void updateSharingSettings(Long memberId, SharingSettingsRequest req) {
        String resourceType = validateResourceType(req.resourceType());
        SharingLevel sharingLevel = req.sharingLevel();
        if (sharingLevel == null) {
            throw new IllegalArgumentException("Sharing level is required");
        }
        List<Long> manualResourceIds = sharingLevel == SharingLevel.MANUAL
            ? validateManualResourceIds(resourceType, memberId, req.sharedResourceIds())
            : List.of();

        SharingSettings settings = sharingSettingsRepository
            .findByMemberIdAndResourceType(memberId, resourceType)
            .orElseGet(() -> new SharingSettings(null, null, resourceType, SharingLevel.NONE));

        FamilyMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (settings.getMember() == null) {
            settings.setMember(member);
        }

        settings.setSharingLevel(sharingLevel);
        sharingSettingsRepository.save(settings);

        sharedResourceRepository.deleteAllByOwnerMemberIdAndResourceType(memberId, resourceType);

        if (sharingLevel == SharingLevel.MANUAL) {
            List<SharedResource> resources = manualResourceIds.stream()
                .map(resourceId -> SharedResource.builder()
                    .ownerMember(member)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .build())
                .toList();
            sharedResourceRepository.saveAll(resources);
        }
    }

    private String validateResourceType(String resourceType) {
        if (!"ACCOUNT".equals(resourceType) && !"GOAL".equals(resourceType)) {
            throw new IllegalArgumentException("Unsupported resource type");
        }
        return resourceType;
    }

    private List<Long> validateManualResourceIds(String resourceType, Long memberId, List<Long> resourceIds) {
        List<Long> distinctIds = new ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (Long resourceId : resourceIds == null ? List.<Long>of() : resourceIds) {
            if (resourceId == null) {
                throw new IllegalArgumentException("Shared resource IDs must not contain null");
            }
            if (seen.add(resourceId)) {
                distinctIds.add(resourceId);
            }
        }

        if (distinctIds.isEmpty()) {
            return distinctIds;
        }

        int resolvedCount = switch (resourceType) {
            case "ACCOUNT" -> accountRepository.findByIdInAndMemberId(distinctIds, memberId).size();
            case "GOAL" -> goalRepository.findByIdInAndMemberId(distinctIds, memberId).size();
            default -> throw new IllegalArgumentException("Unsupported resource type");
        };
        if (resolvedCount != distinctIds.size()) {
            throw new IllegalArgumentException("One or more shared resource IDs not found");
        }
        return distinctIds;
    }
}
