import SwiftUI

/// Minimum AI confidence (0-100) for "Accepter les sûres" to bulk-apply a suggestion. Silently
/// bulk-applying a low-confidence guess is a trust-killer. Deliberately higher than
/// `AISuggestionChip`'s own "show the number at all" threshold (60%) -- worth *displaying* a
/// medium-confidence guess for a human to glance at is a lower bar than worth *auto-applying* it
/// unattended.
private let safeConfidenceThreshold = 70

@MainActor
@Observable
final class CategorizationInboxViewModel {
    private(set) var transactions: [Transaction] = []
    private(set) var isLoading = true
    /// Set only when the inbox itself failed to load (empty-list state). Distinct from
    /// `actionErrorMessage` -- conflating the two used to mean an accept failure was silently
    /// swallowed, since the transaction being put back made `transactions.isEmpty` false.
    var loadErrorMessage: String?
    /// Set when an accept/categorize action fails. Shown as a banner regardless of list state.
    var actionErrorMessage: String?
    var aiStatus: AiJobStatus?
    var aiErrorMessage: String?
    /// The last accepted transaction, kept for the undo snackbar.
    private(set) var lastAccepted: Transaction?

    private let dataSource: BudgetDataSource
    private let onChanged: () -> Void
    private var undoTimeoutTask: Task<Void, Never>?

    init(dataSource: BudgetDataSource, onChanged: @escaping () -> Void) {
        self.dataSource = dataSource
        self.onChanged = onChanged
    }

    func load() async {
        isLoading = true
        loadErrorMessage = nil
        actionErrorMessage = nil
        do {
            transactions = try await dataSource.uncategorizedTransactions()
        } catch {
            loadErrorMessage = (error as? APIError)?.errorDescription
                ?? "Impossible de charger les transactions à catégoriser. Tire pour réessayer."
        }
        isLoading = false
    }

    func accept(_ transaction: Transaction, categoryId: Int64) async {
        transactions.removeAll { $0.id == transaction.id }
        actionErrorMessage = nil
        do {
            try await dataSource.categorize(transactionId: transaction.id, categoryId: categoryId)
            lastAccepted = transaction
            scheduleUndoTimeout()
            onChanged()
        } catch {
            // Failed -- put it back, and make sure the failure is actually seen (see
            // loadErrorMessage's doc comment for why this can't reuse that property).
            transactions.append(transaction)
            actionErrorMessage = "Impossible de changer la catégorie. Réessaie."
        }
    }

    private func scheduleUndoTimeout() {
        undoTimeoutTask?.cancel()
        undoTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            self?.lastAccepted = nil
        }
    }

    func undoLastAccept() async {
        undoTimeoutTask?.cancel()
        guard let transaction = lastAccepted else { return }
        lastAccepted = nil
        transactions.insert(transaction, at: 0)
        // The backend has no "un-categorize" endpoint; undo here is purely local (the transaction
        // reappears in the inbox) -- re-accepting a different category still overwrites cleanly.
    }

    func runAI() async {
        aiErrorMessage = nil
        do {
            aiStatus = try await dataSource.startAiCategorization()
            await pollAIStatus()
        } catch {
            aiStatus = nil
            aiErrorMessage = "L'analyse IA n'a pas abouti. Réessaie, ou catégorise à la main en attendant."
        }
    }

    private func pollAIStatus() async {
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline {
            do {
                let status = try await dataSource.aiCategorizationStatus()
                aiStatus = status
                if status.done {
                    if let jobError = status.error {
                        aiErrorMessage = "L'analyse IA a rencontré un problème : \(jobError)"
                    }
                    await load()
                    return
                }
            } catch {
                aiErrorMessage = "L'analyse IA n'a pas abouti. Réessaie, ou catégorise à la main en attendant."
                aiStatus = nil
                return
            }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
        }
        aiErrorMessage = "L'analyse IA n'a pas abouti. Réessaie, ou catégorise à la main en attendant."
        aiStatus = nil
    }

    /// Bulk-accepts every currently-loaded suggestion at or above `safeConfidenceThreshold`.
    /// Anything below stays in the inbox for manual review. Reconciles with the server via one
    /// `load()` at the end rather than a local-only removal -- any item whose `categorize` call
    /// failed was never actually persisted, so a real reload (not just clearing local state) is
    /// what makes it reappear instead of silently vanishing, same failure class as `accept`.
    func acceptSafeSuggestions() async {
        let safe = transactions.filter { tx in
            guard let confidence = tx.aiConfidence, tx.aiSuggestedCategoryId != nil else { return false }
            return confidence >= safeConfidenceThreshold
        }
        guard !safe.isEmpty else { return }
        var failures = 0
        for tx in safe {
            guard let categoryId = tx.aiSuggestedCategoryId else { continue }
            do {
                try await dataSource.categorize(transactionId: tx.id, categoryId: categoryId)
            } catch {
                failures += 1
            }
        }
        lastAccepted = nil // a batch undo isn't offered -- the single-item snackbar would be misleading here
        await load() // clears actionErrorMessage too -- set below, after, so it isn't wiped
        if failures > 0 {
            actionErrorMessage = failures == safe.count
                ? "Impossible de catégoriser ces transactions. Réessaie."
                : "\(failures) transaction\(failures > 1 ? "s" : "") n'ont pas pu être catégorisées. Réessaie."
        }
        onChanged()
    }
}

struct CategorizationInboxView: View {
    let dataSource: BudgetDataSource
    let onChanged: () -> Void

    @State private var vm: CategorizationInboxViewModel?
    @State private var categories: [Category] = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let vm {
                InboxContent(vm: vm, categories: categories, reduceMotion: reduceMotion)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .navigationTitle("À catégoriser")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if vm == nil { vm = CategorizationInboxViewModel(dataSource: dataSource, onChanged: onChanged) }
            categories = (try? await dataSource.categories()) ?? []
            await vm?.load()
        }
    }
}

private struct InboxContent: View {
    let vm: CategorizationInboxViewModel
    let categories: [Category]
    let reduceMotion: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if let actionErrorMessage = vm.actionErrorMessage {
                    Text(actionErrorMessage).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if !vm.transactions.isEmpty {
                    aiRunner
                }
                if vm.isLoading {
                    ProgressView().controlSize(.large).padding(.top, 60)
                } else if let loadErrorMessage = vm.loadErrorMessage, vm.transactions.isEmpty {
                    Text(loadErrorMessage).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                        .padding(.top, 60)
                } else if vm.transactions.isEmpty {
                    emptyState
                } else {
                    ForEach(vm.transactions) { tx in
                        TransactionCard(transaction: tx, categories: categories) { categoryId in
                            Task { await vm.accept(tx, categoryId: categoryId) }
                        }
                        .transition(reduceMotion ? .opacity : .move(edge: .trailing).combined(with: .opacity))
                    }
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.25) : .spring(response: 0.35, dampingFraction: 0.86),
                       value: vm.transactions)
            .padding(16)
        }
        // Always reachable even in the error/empty states, matching this screen's own "tire pour
        // réessayer" copy -- other Budget screens don't yet do this consistently (tracked as a
        // follow-up), but this one, the highest-traffic screen, must.
        .refreshable { await vm.load() }
        .safeAreaInset(edge: .bottom) {
            if vm.lastAccepted != nil {
                undoSnackbar
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "tray").font(.system(size: 32)).foregroundStyle(Theme.mutedForeground)
            Text("Tout est rangé. Aucune transaction en attente.")
                .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 60)
    }

    @ViewBuilder
    private var aiRunner: some View {
        if let status = vm.aiStatus, !status.done {
            VStack(alignment: .leading, spacing: 8) {
                Text("Analyse… \(status.processed)/\(status.total)")
                    .font(Theme.font(13, .semibold)).foregroundStyle(Theme.brand)
                ProgressBar(value: status.total > 0 ? Double(status.processed) / Double(status.total) : 0, height: 6)
            }
            .padding(12)
            .picsouCard()
        } else {
            VStack(spacing: 8) {
                Button {
                    Task { await vm.runAI() }
                } label: {
                    HStack {
                        Image(systemName: "sparkles")
                        Text("Catégoriser tout avec l'IA")
                    }
                    .font(Theme.font(14, .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Theme.brand.opacity(0.12), in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
                    .foregroundStyle(Theme.brand)
                }
                if hasSafeSuggestions {
                    Button {
                        Task { await vm.acceptSafeSuggestions() }
                    } label: {
                        Text("Accepter les \(safeCount) sûres")
                            .font(Theme.font(13, .semibold)).foregroundStyle(Theme.brand)
                    }
                }
                if let aiErrorMessage = vm.aiErrorMessage {
                    Text(aiErrorMessage).font(Theme.font(12.5)).foregroundStyle(Theme.destructive)
                        .multilineTextAlignment(.center)
                }
            }
        }
    }

    private var safeCount: Int {
        vm.transactions.filter { ($0.aiConfidence ?? 0) >= safeConfidenceThreshold && $0.aiSuggestedCategoryId != nil }.count
    }
    private var hasSafeSuggestions: Bool { safeCount > 0 }

    private var undoSnackbar: some View {
        HStack {
            Text("Catégorisé").font(Theme.font(13)).foregroundStyle(Theme.cardForeground)
            Spacer()
            Button("Annuler") { Task { await vm.undoLastAccept() } }
                .font(Theme.font(13, .semibold)).foregroundStyle(Theme.brand)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(Theme.card, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.border, lineWidth: 1))
        .padding(.horizontal, 16).padding(.bottom, 8)
    }
}

/// Pushed (never a nested sheet — this card already lives on a pushed screen, so a `NavigationLink`
/// stays on the same stack) so "Autre catégorie" behaves like every other category picker entry
/// point in the app (envelope form, transaction detail).
private struct TransactionCard: View {
    let transaction: Transaction
    let categories: [Category]
    let onAccept: (Int64) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                MerchantAvatar(label: transaction.displayLabel, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(transaction.displayLabel).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground).lineLimit(1)
                    Text(relativeDate).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
                }
                Spacer()
                Text(signedAmount).font(Theme.font(15, .bold)).monospacedDigit().foregroundStyle(Theme.foreground)
            }
            .accessibilityElement(children: .combine)

            if let suggestion = CategorySuggestion(aiSuggestionFor: transaction, in: categories) {
                let suggested = suggestion.category
                AISuggestionChip(categoryColor: suggested.color, categoryName: suggested.name, confidence: suggestion.confidence)
                Button {
                    onAccept(suggested.id)
                } label: {
                    Text("Catégoriser")
                        .font(Theme.font(14, .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Theme.brand, in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
                        .foregroundStyle(.white)
                }
                .accessibilityLabel("Catégoriser comme \(suggested.name)")
                .accessibilityHint("Accepte la suggestion IA")
                NavigationLink {
                    CategoryPickerView(categories: categories, current: nil, suggestion: suggestion) { picked in onAccept(picked.id) }
                } label: {
                    Text("Autre catégorie").font(Theme.font(13, .semibold)).foregroundStyle(Theme.mutedForeground)
                }
                .buttonStyle(.plain)
            } else {
                NavigationLink {
                    CategoryPickerView(categories: categories, current: nil) { picked in onAccept(picked.id) }
                } label: {
                    Text("Choisir une catégorie")
                        .font(Theme.font(14, .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Theme.muted, in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
                        .foregroundStyle(Theme.foreground)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .picsouCard()
        .accessibilityElement(children: .contain)
    }

    private var relativeDate: String {
        guard let date = transaction.day else { return transaction.date }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    private var signedAmount: String {
        let formatted = Money.format(abs(transaction.amount))
        return transaction.isExpense ? "-\(formatted)" : "+\(formatted)"
    }
}
