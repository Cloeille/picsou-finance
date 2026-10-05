import SwiftUI

private enum RecurringSegment: Hashable { case series, calendar }

/// Recurring subscriptions/direct debits: triage detected series (confirm/ignore/undo) and the
/// projected upcoming-payments calendar. Detection runs automatically after sync — no "Detect"
/// button in v1. Séries is a `List` (not the app's usual `ScrollView+VStack`) specifically so
/// swipe actions work and surface in VoiceOver's Actions rotor — see
/// docs/briefs/2026-07-22-budget-ios-redesign-design.md §0.1/§8.3.
struct RecurringView: View {
    let dataSource: BudgetDataSource

    @State private var segment: RecurringSegment = .series
    @State private var series: [RecurringSeries] = []
    @State private var activity: [RecurringActivity] = []
    @State private var calendar: [RecurringOccurrence] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $segment) {
                Text("Séries").tag(RecurringSegment.series)
                Text("Agenda").tag(RecurringSegment.calendar)
            }
            .pickerStyle(.segmented)
            .padding(16)

            if isLoading {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let errorMessage {
                ScrollView {
                    Text(errorMessage).font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                        .frame(maxWidth: .infinity).padding(.top, 100)
                }
                .refreshable { await load() }
            } else if segment == .series {
                seriesList
            } else {
                agendaList
            }
        }
        .navigationTitle("Abonnements")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private var seriesList: some View {
        List {
            if !activity.isEmpty {
                Section {
                    ForEach(activity) { entry in
                        activityRow(entry)
                    }
                } header: {
                    Text("Ce qui a changé")
                }
                .listRowBackground(Color.clear)
            }
            if series.isEmpty {
                Section {
                    Text("Aucun abonnement détecté. Ils apparaîtront ici automatiquement après une synchro.")
                        .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                }
                .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(series) { item in
                        seriesRow(item)
                            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                if item.status != .confirmed {
                                    Button { Task { await confirm(item) } } label: {
                                        Label("Confirmer", systemImage: "checkmark.circle.fill")
                                    }.tint(Theme.positive)
                                }
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if item.status == .ignored || item.status == .confirmed {
                                    Button { Task { await undo(item) } } label: {
                                        Label("Annuler", systemImage: "arrow.uturn.backward")
                                    }.tint(Theme.brand)
                                } else {
                                    Button { Task { await ignore(item) } } label: {
                                        Label("Ignorer", systemImage: "eye.slash")
                                    }.tint(Theme.mutedForeground)
                                }
                            }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await load() }
    }

    private func activityRow(_ entry: RecurringActivity) -> some View {
        HStack(spacing: 10) {
            Circle().fill(Color.account(entry.categoryColor ?? Theme.fallbackColorHex)).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.label).font(Theme.font(14, .semibold)).foregroundStyle(Theme.foreground)
                Text(activityDescription(entry)).font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            }
            Spacer()
        }
        .padding(12)
        .picsouCard()
        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
        .listRowSeparator(.hidden)
    }

    private func activityDescription(_ entry: RecurringActivity) -> String {
        switch entry.type {
        case .priceChange:
            let prev = entry.previousAmount.map { Money.format(abs($0)) } ?? "?"
            let now = Money.format(abs(entry.expectedAmount))
            return "Passé de \(prev) à \(now)"
        case .autoConfirmed:
            return "Confirmé automatiquement"
        case .unknown:
            return "Mis à jour"
        }
    }

    private func seriesRow(_ item: RecurringSeries) -> some View {
        HStack(spacing: 12) {
            MerchantAvatar(label: item.label, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.label).font(Theme.font(15, .semibold)).foregroundStyle(Theme.foreground)
                Text("\(amountLabel(item)) · \(item.cadence.label)")
                    .font(Theme.font(12.5)).foregroundStyle(Theme.mutedForeground)
            }
            Spacer()
            StatusChip(status: statusChip(for: item))
        }
        .padding(14)
        .picsouCard()
    }

    /// A "variable" series (e.g. usage-based billing) doesn't have one fixed charge -- showing a
    /// single number as if it did would misrepresent it. Falls back to the flat amount otherwise.
    private func amountLabel(_ item: RecurringSeries) -> String {
        guard item.variable, let min = item.amountMin, let max = item.amountMax else {
            return Money.format(abs(item.expectedAmount))
        }
        return "~\(Money.format(abs(min)))–\(Money.format(abs(max)))"
    }

    private func statusChip(for item: RecurringSeries) -> StatusChip.Status {
        if item.priceIncreased { return .priceIncrease }
        switch item.status {
        case .confirmed: return .confirmed
        case .ignored: return .ignored
        case .suggested, .unknown: return .new
        }
    }

    private var agendaList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if calendar.isEmpty {
                    Text("Aucune échéance à venir.")
                        .font(Theme.font(14)).foregroundStyle(Theme.mutedForeground)
                        .frame(maxWidth: .infinity).padding(.top, 40)
                } else {
                    ForEach(groupedByMonth(calendar), id: \.0) { month, items in
                        VStack(alignment: .leading, spacing: 9) {
                            SectionLabel(month)
                            VStack(spacing: 0) {
                                ForEach(Array(items.enumerated()), id: \.element.id) { index, occurrence in
                                    if index > 0 { Rectangle().fill(Theme.border).frame(height: 1) }
                                    occurrenceRow(occurrence)
                                }
                            }
                            .cardOutline()
                        }
                    }
                }
            }
            .padding(16)
        }
        .refreshable { await load() }
    }

    private func occurrenceRow(_ occurrence: RecurringOccurrence) -> some View {
        HStack(spacing: 10) {
            if occurrence.isCreditCardPayment {
                Image(systemName: "creditcard.fill").font(.system(size: 11)).foregroundStyle(Theme.mutedForeground)
            } else {
                Circle().fill(Color.account(occurrence.categoryColor ?? Theme.fallbackColorHex)).frame(width: 10, height: 10)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(occurrence.label).font(Theme.font(14.5, .semibold)).foregroundStyle(Theme.foreground)
                if occurrence.isCreditCardPayment {
                    Text(cardPaymentDetail(occurrence)).font(Theme.font(12)).foregroundStyle(Theme.mutedForeground)
                }
            }
            Spacer()
            Text(Money.format(abs(occurrence.expectedAmount)))
                .font(Theme.font(14, .semibold)).monospacedDigit().foregroundStyle(Theme.foreground)
        }
        .padding(14)
    }

    private func cardPaymentDetail(_ occurrence: RecurringOccurrence) -> String {
        let points = occurrence.rewardPoints.map { " · \($0.formatted(.number.locale(Locale(identifier: "fr_FR")))) points" } ?? ""
        return "Paiement carte bancaire" + points
    }

    private func groupedByMonth(_ occurrences: [RecurringOccurrence]) -> [(String, [RecurringOccurrence])] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "MMMM yyyy"
        var order: [String] = []
        var map: [String: [RecurringOccurrence]] = [:]
        for item in occurrences {
            let key = item.day.map { formatter.string(from: $0).capitalized } ?? "—"
            if map[key] == nil { order.append(key) }
            map[key, default: []].append(item)
        }
        return order.map { ($0, map[$0] ?? []) }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            async let seriesTask = dataSource.recurringSeries()
            async let activityTask = dataSource.recurringActivity()
            async let calendarTask = dataSource.recurringCalendar(horizonDays: 60)
            (series, activity, calendar) = try await (seriesTask, activityTask, calendarTask)
        } catch {
            errorMessage = "Impossible de charger les abonnements."
        }
        isLoading = false
    }

    private func confirm(_ item: RecurringSeries) async {
        guard let updated = try? await dataSource.confirmRecurring(id: item.id) else { return }
        replace(updated)
    }
    private func ignore(_ item: RecurringSeries) async {
        guard let updated = try? await dataSource.ignoreRecurring(id: item.id) else { return }
        replace(updated)
    }
    private func undo(_ item: RecurringSeries) async {
        guard let updated = try? await dataSource.undoRecurring(id: item.id) else { return }
        replace(updated)
    }
    private func replace(_ updated: RecurringSeries) {
        if let index = series.firstIndex(where: { $0.id == updated.id }) { series[index] = updated }
    }
}
