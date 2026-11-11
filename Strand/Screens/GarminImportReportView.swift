import SwiftUI
import StrandImport
import StrandDesign

/// The text of a Garmin import report: category and skip-reason ids to localized labels. Kotlin twin:
/// `garminCategoryRes` / `garminSkipRes` (GarminImportReportDialog.kt).
enum GarminImportReportText {
    static func category(_ id: String) -> String {
        switch id {
        case GarminExportJson.Category.daily: return String(localized: "Daily summaries")
        case GarminExportJson.Category.sleep: return String(localized: "Sleep nights")
        case GarminExportJson.Category.healthStatus: return String(localized: "Health status days")
        case GarminExportJson.Category.body: return String(localized: "Body measurements")
        case GarminExportJson.Category.fitness: return String(localized: "Fitness records")
        case GarminExportJson.Category.race: return String(localized: "Race predictions")
        case GarminExportJson.Category.records: return String(localized: "Personal records")
        case GarminExportJson.Category.hydration: return String(localized: "Hydration entries")
        case GarminExportJson.Category.activities: return String(localized: "Activities")
        default: return String(localized: "Profile settings")
        }
    }

    static func skip(_ id: String) -> String {
        switch id {
        case GarminExportJson.Skip.privateFile: return String(localized: "Garmin-private backup files NOOP cannot decode")
        case GarminExportJson.Skip.account: return String(localized: "Account and device files that are not health data")
        case GarminExportJson.Skip.noValues: return String(localized: "Health status entries that carry no values")
        default: return String(localized: "Files NOOP does not recognise")
        }
    }

    /// The localized label of a FIT report key (`GarminExportFit.key*` / `skip*`). Kotlin twin: `garminFitRes`.
    static func fit(_ key: String) -> String {
        switch key {
        case GarminExportFit.keyWorkouts: return String(localized: "Workouts stored")
        case GarminExportFit.keyActivity: return String(localized: "Activity files")
        case GarminExportFit.keyMonitoring: return String(localized: "Monitoring files")
        case GarminExportFit.keyMetrics: return String(localized: "Fitness metric files")
        case GarminExportFit.keySleep: return String(localized: "Sleep files")
        case GarminExportFit.keyHrvStatus: return String(localized: "HRV status files")
        case GarminExportFit.keyOther: return String(localized: "Other health files")
        case GarminExportFit.skipInvalidData: return String(localized: "Files with no valid data")
        case GarminExportFit.skipPrivateBackup: return String(localized: "Garmin-private backup files")
        case GarminExportFit.skipUnsupportedType: return String(localized: "Files of a type NOOP does not read")
        case GarminExportFit.skipNotFit: return String(localized: "Files that are not FIT")
        case GarminExportFit.skipNoUsableActivity: return String(localized: "Activity files without a usable workout")
        default: return String(localized: "Files that failed to read")
        }
    }

    /// True when the export's max heart rate differs from the profile's (0 = automatic), so it is offered.
    /// Kotlin twin: `garminOffersMaxHr`.
    static func offersMaxHr(_ settings: GarminExportJson.Settings, profileMaxHr: Int) -> Bool {
        settings.maxHr != nil && settings.maxHr != profileMaxHr
    }
}

/// What a Garmin export brought in and left out, and, when its max heart rate differs from the profile's, an
/// offer to adopt it. Nothing changes unless "Use Garmin's values" is tapped. Kotlin twin:
/// `GarminImportReportDialog`.
struct GarminImportReportView: View {
    let report: GarminExportJson.Result?
    let fit: GarminExportFit.Report?
    let profileMaxHr: Int
    let onUseGarmin: (Int) -> Void
    let onClose: () -> Void

    private var offer: Bool { report.map { GarminImportReportText.offersMaxHr($0.settings, profileMaxHr: profileMaxHr) } ?? false }

    private var imported: [GarminExportJson.ReportCount] { report?.imported ?? [] }
    private var skipped: [GarminExportJson.ReportCount] { report?.skipped ?? [] }
    private var settings: GarminExportJson.Settings { report?.settings ?? GarminExportJson.Settings(maxHr: nil, restingHr: nil, ftpW: nil, zoneFloors: []) }
    private var fitLines: [(String, Int)] { (fit?.counts ?? [:]).sorted { $0.key < $1.key }.map { ($0.key, $0.value) } }

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            Text("Garmin export imported").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    if !imported.isEmpty {
                        Text("Imported").font(StrandFont.subhead).foregroundStyle(StrandPalette.textPrimary)
                        ForEach(imported, id: \.id) { c in
                            Text("\(GarminImportReportText.category(c.id)): \(c.count)")
                                .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                    ForEach(fitLines.filter { !$0.0.hasPrefix("skipped.") }, id: \.0) { l in
                        Text("\(GarminImportReportText.fit(l.0)): \(l.1)")
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if !skipped.isEmpty || fitLines.contains(where: { $0.0.hasPrefix("skipped.") }) {
                        Text("Left out").font(StrandFont.subhead).foregroundStyle(StrandPalette.textPrimary)
                        ForEach(skipped, id: \.id) { c in
                            Text("\(GarminImportReportText.skip(c.id)): \(c.count)")
                                .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                    ForEach(fitLines.filter { $0.0.hasPrefix("skipped.") }, id: \.0) { l in
                        Text("\(GarminImportReportText.fit(l.0)): \(l.1)")
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if let r = settings.restingHr {
                        Text("Resting heart rate in Garmin settings: \(r) bpm")
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if let f = settings.ftpW {
                        Text("Functional threshold power in Garmin settings: \(f) W")
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if offer, let g = settings.maxHr {
                        Group {
                            if profileMaxHr > 0 {
                                Text("Your Garmin export holds a max heart rate of \(g) bpm. NOOP currently uses \(profileMaxHr) bpm.")
                            } else {
                                Text("Your Garmin export holds a max heart rate of \(g) bpm. NOOP currently estimates yours automatically.")
                            }
                        }
                        .font(StrandFont.subhead).foregroundStyle(StrandPalette.textPrimary)
                        Text("Nothing changes unless you tap Use Garmin's values.")
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: NoopMetrics.space3) {
                if offer, let g = settings.maxHr {
                    Button("Use Garmin's values") { onUseGarmin(g) }.buttonStyle(NoopButtonStyle(.primary))
                    Button("Keep mine") { onClose() }.buttonStyle(NoopButtonStyle(.secondary))
                } else {
                    Button("OK") { onClose() }.buttonStyle(NoopButtonStyle(.primary))
                }
            }
        }
        .padding(NoopMetrics.space4)
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 460, minHeight: 320)
        #endif
        .background(StrandPalette.surfaceOverlay)
    }
}
