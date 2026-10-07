import SwiftUI

/// Sheet form to create or edit a budget envelope (POST/PUT/DELETE /api/budgets). Mirrors
/// `GoalFormView`'s shape exactly (Form + toolbar Annuler/Créer|Enregistrer, inline destructive
/// error) for consistency across the app's two CRUD forms.
struct EnvelopeFormView: View {
    let dataSource: BudgetDataSource
    var envelope: BudgetEnvelope?
    var onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var categoryId: Int64?
    @State private var categoryName: String
    @State private var categoryColor: String
    @State private var limitText: String
    @State private var categories: [Category] = []
    @State private var submitting = false
    @State private var error: String?
    @State private var confirmingDelete = false

    init(dataSource: BudgetDataSource, envelope: BudgetEnvelope?, onSaved: @escaping () -> Void) {
        self.dataSource = dataSource
        self.envelope = envelope
        self.onSaved = onSaved
        _categoryId = State(initialValue: envelope?.categoryId)
        _categoryName = State(initialValue: envelope?.categoryName ?? "")
        _categoryColor = State(initialValue: envelope?.categoryColor ?? Theme.fallbackColorHex)
        _limitText = State(initialValue: envelope.map { NSDecimalNumber(decimal: $0.monthlyLimit).stringValue } ?? "")
    }

    private var parsedLimit: Decimal? {
        let normalized = limitText.replacingOccurrences(of: ",", with: ".")
        guard let value = Decimal(string: normalized), value > 0 else { return nil }
        return value
    }
    private var isValid: Bool { categoryId != nil && parsedLimit != nil }

    var body: some View {
        NavigationStack {
            Form {
                Section("Catégorie") {
                    NavigationLink {
                        CategoryPickerView(categories: categories, current: categoryId) { picked in
                            categoryId = picked.id
                            categoryName = picked.name
                            categoryColor = picked.color
                        }
                    } label: {
                        if categoryId != nil {
                            CategoryChip(color: categoryColor, name: categoryName)
                        } else {
                            Text("Choisir une catégorie").foregroundStyle(Theme.mutedForeground)
                        }
                    }
                }
                Section("Limite mensuelle") {
                    HStack {
                        TextField("0", text: $limitText).keyboardType(.decimalPad)
                        Text("€").foregroundStyle(Theme.mutedForeground)
                    }
                }
                if let error {
                    Text(error).font(Theme.font(13)).foregroundStyle(Theme.destructive)
                }
                if envelope != nil {
                    Section {
                        Button("Supprimer l'enveloppe", role: .destructive) { confirmingDelete = true }
                    }
                }
            }
            .navigationTitle(envelope == nil ? "Nouvelle enveloppe" : "Modifier l'enveloppe")
            .navigationBarTitleDisplayMode(.inline)
            .tint(Theme.brand)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(envelope == nil ? "Créer" : "Enregistrer") { submit() }
                        .disabled(submitting || !isValid).fontWeight(.semibold)
                }
            }
            .confirmationDialog("Supprimer cette enveloppe ?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Supprimer", role: .destructive) { delete() }
                Button("Annuler", role: .cancel) {}
            } message: {
                Text("Le suivi de cette catégorie s'arrête.")
            }
            .task { await loadCategories() }
        }
    }

    private func loadCategories() async {
        categories = (try? await dataSource.categories()) ?? []
    }

    private func submit() {
        guard let categoryId, let limit = parsedLimit else { return }
        submitting = true
        error = nil
        Task {
            let request = BudgetRequest(categoryId: categoryId, monthlyLimit: limit)
            do {
                if let envelope {
                    _ = try await dataSource.updateEnvelope(id: envelope.id, request)
                } else {
                    _ = try await dataSource.createEnvelope(request)
                }
                onSaved()
                dismiss()
            } catch {
                self.error = (error as? APIError)?.errorDescription ?? "Échec de l'enregistrement."
            }
            submitting = false
        }
    }

    private func delete() {
        guard let envelope else { return }
        submitting = true
        Task {
            do {
                try await dataSource.deleteEnvelope(id: envelope.id)
                onSaved()
                dismiss()
            } catch {
                self.error = (error as? APIError)?.errorDescription ?? "Échec de la suppression."
            }
            submitting = false
        }
    }
}
