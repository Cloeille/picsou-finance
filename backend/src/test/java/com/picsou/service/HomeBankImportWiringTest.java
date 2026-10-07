package com.picsou.service;

import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HomeBankImportWiringTest {
    @Test
    void springCanConstructHomeBankImportServiceWithItsProductionDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(HomeBankImportService.class)
                .withBean(HomeBankFileParser.class, HomeBankFileParser::new)
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(CategoryRepository.class, () -> mock(CategoryRepository.class))
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(FamilyMemberRepository.class, () -> mock(FamilyMemberRepository.class))
                .withBean(BalanceSnapshotRepository.class, () -> mock(BalanceSnapshotRepository.class))
                .withBean(FinaryPersistenceHelper.class, () -> mock(FinaryPersistenceHelper.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(HomeBankImportService.class));
    }
}
