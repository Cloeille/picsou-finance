import SwiftUI

/// Sheet form to create a savings goal (POST /api/goals): name, target, a future deadline, and a
/// non-empty set of linked accounts.
struct GoalFormView: View {
    let dataSource: GoalsDataSource
    var editing: GoalProgress?
    var onSaved: () -> Void
    var onAuthExpired: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var targetText: String
    @State private var deadline: Date
    @State private var selected: Set<Int64>
    @State private var accounts: [Account] = []
    @State private var loadingAccounts = true
    @State private var submitting = false
    @State private var error: String?

    init(dataSource: GoalsDataSource, editing: GoalProgress? = nil,
         onSaved: @escaping () -> Void, onAuthExpired: @escaping () -> Void) {
        self.dataSource = dataSource
        self.editing = editing
        self.onSaved = onSaved
        self.onAuthExpired = onAuthExpired
        _name = State(initialValue: editing?.name ?? "")
        _targetText = State(initialValue: editing?.targetAmount.map { NSDecimalNumber(decimal: $0).stringValue } ?? "")
        _deadline = State(initialValue: editing?.deadline.flatMap { DateParsing.localDate.date(from: $0) }
                          ?? Calendar.current.date(byAdding: .year, value: 1, to: Date()) ?? Date())
        _selected = State(initialValue: Set(editing?.accountIds ?? []))
    }

    private var parsedTarget: Decimal? {
        let normalized = targetText.replacingOccurrences(of: ",", with: ".")
        guard let value = Decimal(string: normalized), value > 0 else { return nil }
        return value
    }
    private var isValid: Bool {
        parsedTarget != nil && !name.trimmingCharacters(in: .whitespaces).isEmpty && !selected.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Nom") {
                    TextField("ex. Fonds d'urgence", text: $name)
                }
                Section("Montant cible") {
                    HStack {
                        TextField("0", text: $targetText).keyboardType(.decimalPad)
                        Text("€").foregroundStyle(Theme.mutedForeground)
                    }
                }
                Section {
                    DatePicker("Échéance", selection: $deadline, in: Date()..., displayedComponents: .date)
                }
                Section("Comptes liés") {
                    if loadingAccounts {
                        HStack { ProgressView(); Text("Chargement…").foregroundStyle(Theme.mutedForeground) }
                    } else if accounts.isEmpty {
                        Text("Aucun compte.").foregroundStyle(Theme.mutedForeground)
                    } else {
                        ForEach(accounts) { account in
                            Button { toggle(account.id) } label: {
                                HStack(spacing: 10) {
                                    Circle().fill(Color.account(account.color ?? "#888888")).frame(width: 10, height: 10)
                                    Text(account.name).foregroundStyle(Theme.foreground)
                                    Spacer()
                                    if selected.contains(account.id) {
                                        Image(systemName: "checkmark").foregroundStyle(Theme.brand).fontWeight(.semibold)
                                    }
                                }
                            }
                        }
                    }
                }
                if let error {
                    Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                }
            }
            .navigationTitle(editing == nil ? "Nouvel objectif" : "Modifier l'objectif")
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(editing == nil ? "Créer" : "Enregistrer") { submit() }
                        .disabled(submitting || !isValid).fontWeight(.semibold)
                }
            }
            .task { await loadAccounts() }
        }
    }

    private func toggle(_ id: Int64) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }

    private func loadAccounts() async {
        do {
            accounts = try await dataSource.accounts()
        } catch {
            if (error as? APIError) == .unauthorized { onAuthExpired() }
        }
        loadingAccounts = false
    }

    private func submit() {
        guard let target = parsedTarget else { return }
        submitting = true
        error = nil
        Task {
            let request = GoalRequest(
                name: name.trimmingCharacters(in: .whitespaces),
                targetAmount: target,
                deadline: DateParsing.localDate.string(from: deadline),
                accountIds: Array(selected)
            )
            do {
                if let editing {
                    _ = try await dataSource.update(id: editing.id, request)
                } else {
                    _ = try await dataSource.create(request)
                }
                onSaved()
                dismiss()
            } catch {
                self.error = (error as? APIError)?.errorDescription ?? "Échec de l'enregistrement."
            }
            submitting = false
        }
    }
}
