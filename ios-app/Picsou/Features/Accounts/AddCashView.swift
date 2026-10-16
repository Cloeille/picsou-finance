import SwiftUI

/// Sheet form to add a manual cash transaction (POST /api/accounts/{id}/transactions).
/// Direction (dépense/revenu) is encoded as the sign of `amount`.
struct AddCashView: View {
    let accountId: Int64
    let dataSource: AccountsDataSource
    var onAdded: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var amountText = ""
    @State private var label = ""
    @State private var date = Date()
    @State private var isExpense = true
    @State private var submitting = false
    @State private var error: String?

    private var parsedAmount: Decimal? {
        let normalized = amountText.replacingOccurrences(of: ",", with: ".")
        guard let value = Decimal(string: normalized), value > 0 else { return nil }
        return value
    }
    private var isValid: Bool { parsedAmount != nil && !label.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("", selection: $isExpense) {
                        Text("Dépense").tag(true)
                        Text("Revenu").tag(false)
                    }
                    .pickerStyle(.segmented)
                }
                Section("Montant") {
                    HStack {
                        TextField("0,00", text: $amountText).keyboardType(.decimalPad)
                        Text("€").foregroundStyle(Theme.mutedForeground)
                    }
                }
                Section("Libellé") {
                    TextField("ex. Courses, Remboursement…", text: $label)
                }
                Section {
                    DatePicker("Date", selection: $date, displayedComponents: .date)
                }
                if let error {
                    Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                }
            }
            .navigationTitle("Ajouter")
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Ajouter") { submit() }.disabled(submitting || !isValid).fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        guard let amount = parsedAmount else { return }
        let signed = isExpense ? -abs(amount) : abs(amount)
        submitting = true
        error = nil
        Task {
            let request = TransactionRequest(
                date: DateParsing.localDate.string(from: date),
                description: label.trimmingCharacters(in: .whitespaces),
                amount: signed,
                txType: isExpense ? "WITHDRAWAL" : "DEPOSIT",
                currency: "EUR",
                categoryId: nil
            )
            do {
                _ = try await dataSource.addCash(accountId: accountId, request)
                onAdded()
                dismiss()
            } catch {
                self.error = (error as? APIError)?.errorDescription ?? "Échec de l'ajout."
            }
            submitting = false
        }
    }
}
