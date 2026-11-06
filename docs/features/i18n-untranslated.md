# German and Spanish strings awaiting translation

> Last updated: 2026-11-03

These keys exist in `de.json` and `es.json` (locale parity holds) but still carry the English text. They are kept, not deleted: removing them would make German and Spanish fall back to French. Translators can pick any group; once a string is translated, drop it from this list.

This is a partial inventory: 221 keys, the same set in both files. A key is listed when its `de` and `es` values are both identical to `en`, the `fr` value differs, and the English text has at least two words of two letters or more. The last rule drops brand names and tickers ("Trade Republic", "ETF"), but it also drops single-word labels that are really untranslated, such as `admin.ai.save`, `budget.cadence.monthly` or `accounts.tabOverview`. A plain "identical to English" comparison returns about 330 keys per language, and translators should check those single words too.

- `accounts`: `changeCategory`, `importAlreadyDone`, `importCsvTR`, `importError`, `importSuccess`, `importSuccessWithSkipped`, `importSuccessWithSkipped_other`, `importSuccess_other`
- `addAccount.desc`: `revolut`
- `admin.ai`: `apiKey`, `apiKeyHintPresent`, `apiKeyRequired`, `baseUrl`, `description`, `disabledHint`, `maxConcurrency`, `maxConcurrencyHint`, `test`, `title`
- `admin.aiActivity`: `description`, `empty`, `summary`, `title`, `view`
- `budget.a11y`: `archive`, `confirm`, `delete`, `edit`, `ignore`, `unarchive`
- `budget.allocation`: `contributions`, `error`, `noContributions`, `noContributionsHint`, `noStock`, `stock`, `subtitle`, `totalContributed`
- `budget.cadence`: `biweekly`
- `budget.categorize`: `aiDone`, `aiProgress`, `categorizeAi`, `createRule`, `editRule`, `empty`, `emptyHint`, `error`, `recategorize`, `selectCategory`, `subtitle`
- `budget.category`: `edit`, `error`, `formHint`, `hint`, `parent`, `parentHint`, `parentLocked`, `parentNone`, `subcategory`
- `budget.detail`: `back`, `empty`, `error`, `notFound`, `subcategories`, `total`
- `budget.envelope`: `add`, `deleteDescription`, `deleteTitle`, `edit`, `empty`, `emptyHint`, `error`, `formHint`, `includesSubcategories`, `monthlyLimit`, `selectCategory`, `subtitle`
- `budget.flow`: `breakdown`, `dataTableCaption`, `diagram`, `empty`, `error`, `node.incomeOther`, `node.moreExpenses`, `subtitle`
- `budget.overview`: `contributedThisCycle`, `error`, `incomeVsExpense`, `investable`, `netThisCycle`, `onTrack`, `overBudget`, `overBudget_other`, `pastPeriodNote`, `toCategorize`, `toCategorize_other`, `topEnvelopes`, `upcomingCount`, `upcomingCount_other`
- `budget.period`: `next`, `prev`
- `budget.recurring`: `activity.acknowledge`, `activity.autoConfirmedDesc`, `activity.priceChange`, `activity.priceChangeDesc`, `activity.title`, `error`, `formHint`, `nextDue`, `noCategory`, `noSeries`, `noUpcoming`, `noUpcomingHint`, `runtime.dueSoon`, `series`, `subtitle`, `upcoming`, `variable`
- `budget.rule`: `add`, `createRule`, `empty`, `error`, `formHint`, `hint`, `match.keywords_all`, `match.keywords_any`, `matchAll`, `matchAny`, `matchMode`, `matchType`, `previewCapped`, `previewCount`, `previewCount_other`, `previewLoading`, `ruleActive`, `title`, `wordPickerTitle`
- `budget.settings`: `aiEnableLabel`, `aiHint`, `aiModeHint.AUTO_ALL`, `aiModeHint.AUTO_HIGH_CONFIDENCE`, `aiModeHint.SUGGEST`, `aiModeLabel`, `aiModeOption.AUTO_ALL`, `aiModeOption.AUTO_HIGH_CONFIDENCE`, `aiModeOption.SUGGEST`, `aiThresholdHint`, `aiThresholdLabel`, `aiTitle`, `currentCycle`, `cycleHint`, `cycleStartDay`, `error`, `logoFetchLabel`, `logoHint`, `logoTitle`
- `budget.tab`: `categorize`
- `common`: `skipToContent`
- `connectedApps`: `empty`, `issued`, `lastUsed`, `lastUsedNever`, `revokeDescription`, `revokeTitle`, `sectionDescription`, `sectionTitle`
- `dashboard`: `portfolioPerformance`
- `nav`: `budget.desc` (a flat key, stored as `"budget.desc"` inside `nav`)
- `oauthConsent`: `description`, `error`, `loading`, `missingParams`, `noScopes`, `scopesIntro`, `title`, `titleGeneric`
- `savings`: `ceiling`, `configSection`, `deleteConfig`, `deleteConfigConfirm`, `projectionDisclaimer`, `projectionFullYear`, `projectionNextCapitalization`, `projectionTitle`, `rateBasis`, `regulatedNet`, `saveConfig`, `suggestionsBanner`, `suggestionsBanner_other`, `taxRateHint`
- `savings.products`: `COMMERCIAL`
- `setup.integrations`: `cards.revolut.description`
- `setup.revolut`: `body`, `cta`, `skip`, `tip`, `title`
- `sync.all`: `openRevolut`, `revolutManualInfo`
- `sync.revolut`: `forget`, `lastSync`, `notConnected`, `phase.awaitingApproval`, `phase.checkingSession`, `phase.harvesting`, `phase.loggingIn`, `phone`, `remember`, `selection.allImported`, `selection.alreadyImported`, `selection.confirm`, `selection.selectAll`, `selection.title`, `sync`, `syncSuccess`
- `transactions`: `allAccounts`, `allCategories`, `detail`, `empty`, `error`, `viewAll`
