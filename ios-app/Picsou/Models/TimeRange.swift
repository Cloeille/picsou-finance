import Foundation

/// Dashboard history window. Raw values are what the backend's `?range=` param expects
/// (see `DashboardService`). Default is `.year`, matching the web app.
enum TimeRange: String, CaseIterable, Identifiable {
    case day = "24H"
    case week = "7D"
    case month = "1M"
    case quarter = "3M"
    case ytd = "YTD"
    case year = "1Y"
    case all = "ALL"

    var id: String { rawValue }
    var label: String { rawValue }
}
