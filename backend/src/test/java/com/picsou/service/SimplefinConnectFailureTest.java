package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.port.SimplefinPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Re-connecting must be all-or-nothing: a failed claim (invalid or already-used setup token,
 * bridge unreachable) or a failed encryption must leave the member's existing, working access
 * URL exactly as it was. Complements {@code SimplefinSyncServiceTest}, which only covers the
 * happy-path store.
 */
@ExtendWith(MockitoExtension.class)
class SimplefinConnectFailureTest {

    private static final Long MEMBER_ID = 7L;
    private static final String OLD_CIPHERTEXT = "old-ciphertext";
    private static final String NEW_ACCESS = "https://user9999:newsecret@beta-bridge.simplefin.org/simplefin";

    @Mock SimplefinPort simplefinPort;
    @Mock SimplefinConnectionRepository connectionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock BankTransactionImportService transactionImportService;
    @Mock CryptoEncryption encryption;
    @Mock SimplefinStatusWriter statusWriter;

    private SimplefinSyncService service;
    private SimplefinConnection existing;

    @BeforeEach
    void setUp() {
        service = new SimplefinSyncService(
            simplefinPort, connectionRepository, accountRepository, familyMemberRepository,
            accountService, transactionImportService, encryption, statusWriter);
        existing = SimplefinConnection.builder().id(42L).accessUrl(OLD_CIPHERTEXT).status("CONNECTED").build();
    }

    @Test
    void connect_whenTheClaimFails_keepsTheExistingAccessUrl() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(simplefinPort.claim("used-token")).thenThrow(new SyncException("token already used"));

        assertThatThrownBy(() -> service.connect("used-token", MEMBER_ID))
            .isInstanceOf(SyncException.class);

        verifyNoInteractions(connectionRepository, encryption);
        assertThat(existing.getAccessUrl()).isEqualTo(OLD_CIPHERTEXT);
    }

    @Test
    void connect_whenEncryptionFails_doesNotTouchTheExistingConnection() {
        when(simplefinPort.claim("fresh")).thenReturn(NEW_ACCESS);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(existing));
        when(encryption.encrypt(NEW_ACCESS)).thenThrow(new IllegalStateException("key unavailable"));

        assertThatThrownBy(() -> service.connect("fresh", MEMBER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(existing.getAccessUrl()).isEqualTo(OLD_CIPHERTEXT);
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void connect_forAnUnknownMember_neverSpendsTheToken() {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect("fresh", MEMBER_ID))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(simplefinPort, never()).claim(anyString());
        verifyNoInteractions(connectionRepository, encryption);
    }

    /** Success path of a re-connect: the same row is updated in place and an ERROR is cleared. */
    @Test
    void connect_onSuccess_replacesTheAccessUrlInPlace_andClearsAnErrorStatus() {
        existing.setStatus("ERROR");
        when(simplefinPort.claim("fresh")).thenReturn(NEW_ACCESS);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member()));
        when(connectionRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(existing));
        when(encryption.encrypt(NEW_ACCESS)).thenReturn("new-ciphertext");

        service.connect("fresh", MEMBER_ID);

        verify(connectionRepository).save(existing);
        assertThat(existing.getAccessUrl()).isEqualTo("new-ciphertext");
        assertThat(existing.getStatus()).isEqualTo("CONNECTED");
        assertThat(existing.getId()).isEqualTo(42L);
    }

    private static FamilyMember member() {
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        return member;
    }
}
