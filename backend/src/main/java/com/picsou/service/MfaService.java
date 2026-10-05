package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.MfaException;
import com.picsou.model.AppUser;
import com.picsou.model.UserMfa;
import com.picsou.model.UserMfaRecoveryCode;
import com.picsou.repository.UserMfaRecoveryCodeRepository;
import com.picsou.repository.UserMfaRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final int TOTP_PERIOD_SECONDS = 30;
    private static final int TOTP_WINDOW = 1;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 8;

    private final UserMfaRepository userMfaRepository;
    private final UserMfaRecoveryCodeRepository recoveryCodeRepository;
    private final CryptoEncryption cryptoEncryption;
    private final PasswordEncoder passwordEncoder;
    private final SecretGenerator secretGenerator;
    private final CodeGenerator codeGenerator;
    private final TimeProvider timeProvider;
    private final QrGenerator qrGenerator;
    private final SecureRandom random = new SecureRandom();

    private final String issuer;

    public MfaService(
        UserMfaRepository userMfaRepository,
        UserMfaRecoveryCodeRepository recoveryCodeRepository,
        CryptoEncryption cryptoEncryption,
        PasswordEncoder passwordEncoder,
        SecretGenerator secretGenerator,
        CodeGenerator codeGenerator,
        TimeProvider totpTimeProvider,
        QrGenerator qrGenerator,
        @Value("${app.mfa.issuer:Picsou}") String issuer
    ) {
        this.userMfaRepository = userMfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.cryptoEncryption = cryptoEncryption;
        this.passwordEncoder = passwordEncoder;
        this.secretGenerator = secretGenerator;
        this.codeGenerator = codeGenerator;
        this.timeProvider = totpTimeProvider;
        this.qrGenerator = qrGenerator;
        this.issuer = issuer;
    }

    public boolean isEnabled(AppUser user) {
        return userMfaRepository.existsByUserIdAndEnabledTrue(user.getId());
    }

    public MfaStatus getStatus(AppUser user) {
        return userMfaRepository.findByUserId(user.getId())
            .filter(UserMfa::isEnabled)
            .map(mfa -> new MfaStatus(
                true,
                mfa.getEnrolledAt(),
                (int) recoveryCodeRepository.countByUserMfaIdAndUsedAtIsNull(mfa.getId())
            ))
            .orElse(new MfaStatus(false, null, 0));
    }

    public void requireReauth(AppUser user, String currentPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new MfaException("Current password is incorrect");
        }
    }

    @Transactional
    public EnrollmentSecret beginEnrollment(AppUser user) {
        Optional<UserMfa> existing = userMfaRepository.findByUserId(user.getId());
        if (existing.isPresent() && existing.get().isEnabled()) {
            throw new MfaException("MFA is already enabled");
        }

        String base32Secret = secretGenerator.generate();
        String encrypted = cryptoEncryption.encrypt(base32Secret);

        UserMfa mfa = existing.orElseGet(() -> UserMfa.builder().user(user).build());
        mfa.setTotpSecretEnc(encrypted);
        mfa.setEnabled(false);
        mfa.setLastUsedStep(null);
        mfa.setEnrolledAt(null);
        userMfaRepository.save(mfa);

        String qrDataUri = buildQrDataUri(user.getUsername(), base32Secret);
        return new EnrollmentSecret(qrDataUri, base32Secret);
    }

    @Transactional
    public List<String> completeEnrollment(AppUser user, String code) {
        UserMfa mfa = userMfaRepository.findByUserId(user.getId())
            .orElseThrow(() -> new MfaException("MFA enrollment not started"));
        if (mfa.isEnabled()) {
            throw new MfaException("MFA is already enabled");
        }

        String secret = cryptoEncryption.decrypt(mfa.getTotpSecretEnc());
        Long matchedStep = matchTotpStep(secret, code, null);
        if (matchedStep == null) {
            throw new MfaException("Invalid verification code");
        }

        mfa.setEnabled(true);
        mfa.setEnrolledAt(Instant.now());
        mfa.setLastUsedStep(matchedStep);
        userMfaRepository.save(mfa);

        return generateAndStoreRecoveryCodes(mfa);
    }

    @Transactional
    public boolean verifyTotp(AppUser user, String code) {
        Optional<UserMfa> opt = userMfaRepository.findByUserId(user.getId());
        if (opt.isEmpty() || !opt.get().isEnabled()) return false;

        UserMfa mfa = opt.get();
        String secret = cryptoEncryption.decrypt(mfa.getTotpSecretEnc());
        Long matchedStep = matchTotpStep(secret, code, mfa.getLastUsedStep());
        if (matchedStep == null) return false;

        mfa.setLastUsedStep(matchedStep);
        userMfaRepository.save(mfa);
        return true;
    }

    @Transactional
    public boolean verifyRecoveryCode(AppUser user, String code) {
        Optional<UserMfa> opt = userMfaRepository.findByUserId(user.getId());
        if (opt.isEmpty() || !opt.get().isEnabled()) return false;

        List<UserMfaRecoveryCode> activeCodes =
            recoveryCodeRepository.findByUserMfaIdAndUsedAtIsNull(opt.get().getId());
        for (UserMfaRecoveryCode rc : activeCodes) {
            if (passwordEncoder.matches(code, rc.getCodeHash())) {
                rc.setUsedAt(Instant.now());
                recoveryCodeRepository.save(rc);
                return true;
            }
        }
        return false;
    }

    public boolean verifyTotpOrRecovery(AppUser user, String code, boolean isRecovery) {
        return isRecovery ? verifyRecoveryCode(user, code) : verifyTotp(user, code);
    }

    @Transactional
    public void disable(AppUser user) {
        userMfaRepository.findByUserId(user.getId()).ifPresent(mfa -> {
            recoveryCodeRepository.deleteByUserMfaId(mfa.getId());
            userMfaRepository.delete(mfa);
        });
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(AppUser user) {
        UserMfa mfa = userMfaRepository.findByUserId(user.getId())
            .filter(UserMfa::isEnabled)
            .orElseThrow(() -> new MfaException("MFA is not enabled"));
        recoveryCodeRepository.deleteByUserMfaId(mfa.getId());
        return generateAndStoreRecoveryCodes(mfa);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<String> generateAndStoreRecoveryCodes(UserMfa mfa) {
        Set<String> plaintexts = new HashSet<>();
        while (plaintexts.size() < RECOVERY_CODE_COUNT) {
            int n = random.nextInt(100_000_000);
            plaintexts.add(String.format("%0" + RECOVERY_CODE_LENGTH + "d", n));
        }
        List<String> ordered = new ArrayList<>(plaintexts);
        for (String code : ordered) {
            UserMfaRecoveryCode rc = UserMfaRecoveryCode.builder()
                .userMfa(mfa)
                .codeHash(passwordEncoder.encode(code))
                .build();
            recoveryCodeRepository.save(rc);
        }
        return ordered;
    }

    /**
     * Returns the matched time-step on success, null on failure.
     * If {@code minStep} is non-null, steps {@code <= minStep} are rejected (anti-replay).
     */
    Long matchTotpStep(String secret, String code, Long minStep) {
        if (code == null || code.length() != 6) return null;
        long currentStep = timeProvider.getTime() / TOTP_PERIOD_SECONDS;
        // Try the current step first (most likely to match), then ±1.
        int[] deltas = new int[]{0, -1, 1};
        for (int delta : deltas) {
            long step = currentStep + delta;
            if (minStep != null && step <= minStep) continue;
            String expected;
            try {
                expected = codeGenerator.generate(secret, step);
            } catch (CodeGenerationException ex) {
                // Not a wrong code: the stored secret or the generator is broken, which would
                // otherwise be indistinguishable from a user typo in the 401 the caller returns.
                log.warn("TOTP code generation failed", ex);
                return null;
            }
            if (constantTimeEquals(expected, code)) return step;
        }
        return null;
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private String buildQrDataUri(String accountLabel, String base32Secret) {
        QrData data = new QrData.Builder()
            .label(issuer + ":" + accountLabel)
            .secret(base32Secret)
            .issuer(issuer)
            .algorithm(dev.samstevens.totp.code.HashingAlgorithm.SHA1)
            .digits(6)
            .period(TOTP_PERIOD_SECONDS)
            .build();
        try {
            byte[] png = qrGenerator.generate(data);
            return "data:" + qrGenerator.getImageMimeType() + ";base64," + Base64.getEncoder().encodeToString(png);
        } catch (QrGenerationException ex) {
            throw new MfaException("Failed to generate QR code", ex);
        }
    }

    public record EnrollmentSecret(String qrCodeDataUri, String base32Secret) {}

    public record MfaStatus(boolean enabled, Instant enrolledAt, int remainingRecoveryCodes) {}
}
