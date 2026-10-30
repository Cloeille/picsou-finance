package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.model.ScpiProviderFund;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScpiFundServiceTest {

    @Mock ScpiProviderFundRepository fundRepository;
    @Mock ScpiPositionRepository positionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @InjectMocks ScpiFundService service;

    @Test
    void ignore_unlinksTheAccountKeepsItAndSetsTheTombstone() {
        Account account = Account.builder().id(10L).name("CORUM XL").build();
        ScpiPosition position = ScpiPosition.builder().account(account).corumFundCode("XL").build();
        when(positionRepository.findByMemberIdAndCorumFundCode(7L, "XL")).thenReturn(Optional.of(position));
        when(fundRepository.findByMemberIdAndProviderAndFundCode(7L, ScpiProvider.CORUM, "XL"))
            .thenReturn(Optional.empty());
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());

        service.ignore(7L, ScpiProvider.CORUM, "XL");

        assertThat(position.getCorumFundCode()).isNull();
        assertThat(account.getDeletedAt()).isNull();
        ArgumentCaptor<ScpiProviderFund> row = ArgumentCaptor.forClass(ScpiProviderFund.class);
        verify(fundRepository).save(row.capture());
        assertThat(row.getValue().isIgnored()).isTrue();
        assertThat(row.getValue().getFundCode()).isEqualTo("XL");
    }

    @Test
    void restore_liftsTheTombstoneOnly() {
        ScpiProviderFund row = ScpiProviderFund.builder().provider(ScpiProvider.SOFIDY)
            .fundCode("DY").ignored(true).build();
        when(fundRepository.findByMemberIdAndProviderAndFundCode(7L, ScpiProvider.SOFIDY, "DY"))
            .thenReturn(Optional.of(row));

        service.restore(7L, ScpiProvider.SOFIDY, "DY");

        assertThat(row.isIgnored()).isFalse();
        verify(positionRepository, never()).save(any());
    }

    @Test
    void ignore_refusesABlankCode() {
        assertThatThrownBy(() -> service.ignore(7L, ScpiProvider.CORUM, " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tombstone_withoutACodeDoesNothing() {
        service.tombstone(7L, ScpiProvider.CORUM, null);

        verify(fundRepository, never()).save(any());
    }
}
