package com.picsou.config;

import com.picsou.mcp.tools.AccountTools;
import com.picsou.mcp.tools.AnalysisTools;
import com.picsou.mcp.tools.BudgetTools;
import com.picsou.mcp.tools.GoalTools;
import com.picsou.mcp.tools.InsightTools;
import com.picsou.mcp.tools.OAuth2Tools;
import com.picsou.mcp.tools.SyncTools;
import com.picsou.mcp.tools.TransactionTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Picsou's curated MCP tool surface as a single {@link ToolCallbackProvider} bean, which
 * the Spring AI MCP server auto-configuration discovers and advertises over {@code /mcp}.
 *
 * <p>This is the one place the exposed tools are wired. The set is deliberately small and audited:
 * accounts, transactions, goals, read-only insights and wealth analysis, refresh-existing-syncs, the
 * budget module, and OAuth2 authorization-server discovery/session status — each method gated by {@code @RequiresScope}.
 * Nothing here reaches authentication, credentials, MFA, admin settings, member management, or data
 * export, because no tool for those exists. {@code McpToolCatalogTest} pins this surface so any
 * change to it is deliberate and reviewed.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider picsouMcpTools(AccountTools accountTools,
                                               TransactionTools transactionTools,
                                               GoalTools goalTools,
                                               InsightTools insightTools,
                                               SyncTools syncTools,
                                               OAuth2Tools oAuth2Tools,
                                               BudgetTools budgetTools,
                                               AnalysisTools analysisTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(accountTools, transactionTools, goalTools, insightTools, syncTools,
                oAuth2Tools, budgetTools, analysisTools)
            .build();
    }
}
