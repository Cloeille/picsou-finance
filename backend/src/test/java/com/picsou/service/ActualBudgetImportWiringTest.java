package com.picsou.service;

import com.picsou.controller.ActualBudgetImportController;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.actual.ActualBudgetFileParser;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import beans have test-only constructors; this proves Spring still picks the right one,
 * which no other local test does (the full-context tests need Docker).
 */
class ActualBudgetImportWiringTest {
    @Test
    void springWiresTheImportBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(AccountRepository.class, () -> Mockito.mock(AccountRepository.class));
            ctx.registerBean(CategoryRepository.class, () -> Mockito.mock(CategoryRepository.class));
            ctx.registerBean(TransactionRepository.class, () -> Mockito.mock(TransactionRepository.class));
            ctx.registerBean(FamilyMemberRepository.class, () -> Mockito.mock(FamilyMemberRepository.class));
            ctx.registerBean(FinaryPersistenceHelper.class, () -> Mockito.mock(FinaryPersistenceHelper.class));
            ctx.registerBean(UserContext.class, () -> Mockito.mock(UserContext.class));
            ctx.registerBean("syncBuckets", ConcurrentHashMap.class, () -> new ConcurrentHashMap<>());
            ctx.register(ActualBudgetFileParser.class, ActualBudgetImportService.class, ActualBudgetImportController.class);
            ctx.refresh();
            assertThat(ctx.getBean(ActualBudgetImportController.class)).isNotNull();
        }
    }
}
