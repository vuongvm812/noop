import SwiftUI
import StrandAnalytics
import StrandDesign

// MARK: - Weight diary sheet (#57)
//
// Opened from the Today Weight tile on every Apple Today (classic and Liquid). Log one weight per day for
// any past or present day, edit or delete it, and read the daily weight chart: scrub it to read a day's
// value (the shared `TrendChart` selection readout). The chart draws `WeightLog.dailySeries`, the logged
// days plus any Apple Health weigh-ins, so it agrees with the tile. Twin of Android `WeightLogSheet`.

struct WeightLogSheet: View {
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var profile: ProfileStore

    let unitSystem: UnitSystem
    let onClose: () -> Void

    @State private var logged: [WeightLog.Entry] = []
    @State private var health: [WeightLog.Entry] = []
    @State private var day = Date()
    @State private var text = ""
    @State private var showInvalid = false
    @State private var bodyComposition: WeightLog.BodyComposition?

    private var imperial: Bool { unitSystem == .imperial }
    private var dayKey: String { Repository.localDayKey(day) }
    private var loggedForDay: WeightLog.Entry? { logged.first { $0.day == dayKey } }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(StrandPalette.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.sectionSpacing) {
                    compositionRow
                    chartCard
                    entryCard
                    historyCard
                }
                .padding(20)
            }
            Divider().overlay(StrandPalette.hairline)
            HStack {
                Spacer()
                Button(action: onClose) { Text("Done").frame(minWidth: 120) }
                    .buttonStyle(NoopButtonStyle(.primary))
                    .keyboardShortcut(.defaultAction)
            }
            .padding(NoopMetrics.space4)
        }
        #if os(macOS)
        .frame(width: 520, height: 660)
        #else
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .noopSheetPresentation(largeFirst: true)
        #endif
        .background(StrandPalette.surfaceBase)
        .task { await reload() }
        .onChange(of: dayKey) { _ in prefill() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Weight").font(StrandFont.rounded(26, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Your profile weight follows your latest logged weight.")
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .font(StrandFont.title2)
                    .foregroundStyle(StrandPalette.textTertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")
        }
        .padding(20)
    }

    // MARK: Composition

    /// Newest weight / body fat / lean mass, each with its reading's date; "-" when a value is missing.
    private var compositionRow: some View {
        let latestWeight = WeightLog.dailySeries(logged: logged, health: health).last
        return HStack(spacing: NoopMetrics.space2) {
            compositionBox(label: "Weight",
                           value: latestWeight.map { formatted(displayValue($0.kg)) },
                           day: latestWeight?.day)
            compositionBox(label: "Body Fat",
                           value: bodyComposition?.bodyFatPct.map {
                               String(format: "%.1f", locale: AppLanguage.activeLocale, $0.value) + " %"
                           },
                           day: bodyComposition?.bodyFatPct?.day)
            compositionBox(label: "Lean Mass",
                           value: bodyComposition?.leanMassKg.map { formatted(displayValue($0.value)) },
                           day: bodyComposition?.leanMassKg?.day)
        }
    }

    private func compositionBox(label: LocalizedStringKey, value: String?, day: String?) -> some View {
        NoopCard(padding: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).strandOverline()
                Text(verbatim: value ?? "-")
                    .font(StrandFont.number(18))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(verbatim: day.map { dayLabel($0) } ?? " ")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: Chart

    private var chartCard: some View {
        let series = WeightLog.dailySeries(logged: logged, health: health)
        let points = series.compactMap { e -> TrendPoint? in
            guard let date = Self.date(fromDayKey: e.day) else { return nil }
            return TrendPoint(date: date, value: displayValue(e.kg))
        }
        return NoopCard(tint: StrandPalette.accent) {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                if points.count >= 2 {
                    let values = points.map(\.value)
                    let lo = values.min() ?? 0, hi = values.max() ?? 1
                    let pad = max(1, (hi - lo) * 0.15)
                    TrendChart(
                        points: points,
                        gradient: Gradient(colors: [StrandPalette.accent.opacity(0.6), StrandPalette.accent]),
                        valueRange: (lo - pad)...(hi + pad),
                        showsArea: true,
                        height: 200,
                        valueFormat: { formatted($0) },
                        accessibilityLabel: String(localized: "Weight"),
                        largeSelection: true
                    )
                } else {
                    Text("Log a weight on two or more days to see the trend.")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: Entry

    private var entryCard: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                DatePicker("Day", selection: $day, in: ...Date(), displayedComponents: .date)
                    .font(StrandFont.subhead)
                    .foregroundStyle(StrandPalette.textPrimary)
                HStack(spacing: NoopMetrics.space2) {
                    TextField(imperial ? String(localized: "Weight (lb)") : String(localized: "Weight (kg)"),
                              text: $text)
                        .textFieldStyle(.roundedBorder)
                        .font(StrandFont.number(17))
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                        .onSubmit { save() }
                    Text(verbatim: imperial ? "lb" : "kg")
                        .font(StrandFont.captionNumber)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                if showInvalid {
                    Text(invalidMessage)
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.statusWarning)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: NoopMetrics.space2) {
                    Button { save() } label: { Text("Save").frame(minWidth: 90) }
                        .buttonStyle(NoopButtonStyle(.primary))
                    if loggedForDay != nil {
                        Button { delete(dayKey) } label: { Text("Delete").frame(minWidth: 90) }
                            .buttonStyle(NoopButtonStyle(.destructive))
                    }
                    Spacer()
                }
            }
        }
    }

    // MARK: History

    private var historyCard: some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                Text("Logged weights").strandOverline()
                if logged.isEmpty {
                    Text("No weight logged yet.")
                        .font(StrandFont.subhead)
                        .foregroundStyle(StrandPalette.textTertiary)
                } else {
                    ForEach(logged.reversed(), id: \.day) { entry in
                        HStack {
                            Button {
                                if let d = Self.date(fromDayKey: entry.day) { day = d }
                            } label: {
                                HStack {
                                    Text(dayLabel(entry.day))
                                        .font(StrandFont.subhead)
                                        .foregroundStyle(StrandPalette.textSecondary)
                                    Spacer()
                                    Text(verbatim: formatted(displayValue(entry.kg)))
                                        .font(StrandFont.number(15))
                                        .foregroundStyle(StrandPalette.textPrimary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Button { delete(entry.day) } label: {
                                Image(systemName: "trash")
                                    .font(StrandFont.caption)
                                    .foregroundStyle(StrandPalette.textTertiary)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Delete")
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
    }

    // MARK: Actions

    private func reload() async {
        logged = await repo.weightLogEntries()
        health = await repo.series(key: "weight", source: "apple-health", fullHistory: true)
            .map { WeightLog.Entry(day: $0.day, kg: $0.value) }
        bodyComposition = await repo.latestBodyComposition()
        prefill()
    }

    /// Put the selected day's logged weight in the field, or clear it.
    private func prefill() {
        showInvalid = false
        text = loggedForDay.map { String(format: "%.1f", locale: AppLanguage.activeLocale, displayValue($0.kg)) } ?? ""
    }

    private func save() {
        guard let kg = WeightLog.parseKg(text, imperial: imperial) else {
            showInvalid = true
            return
        }
        showInvalid = false
        let key = dayKey
        Task {
            await repo.setLoggedWeight(day: key, kg: kg, profile: profile)
            await reload()
        }
    }

    private func delete(_ key: String) {
        Task {
            await repo.deleteLoggedWeight(day: key, profile: profile)
            await reload()
        }
    }

    // MARK: Formatting

    private func displayValue(_ kg: Double) -> Double { imperial ? UnitFormatter.kgToPounds(kg) : kg }

    private func formatted(_ value: Double) -> String {
        String(format: "%.1f", locale: AppLanguage.activeLocale, value) + (imperial ? " lb" : " kg")
    }

    private var invalidMessage: String {
        let lo = displayValue(WeightLog.minKg), hi = displayValue(WeightLog.maxKg)
        return String(localized: "Enter a weight between \(formatted(lo)) and \(formatted(hi)).")
    }

    /// A stored `yyyy-MM-dd` day key back to the local midnight it names.
    private static let dayKeyParser: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }()

    static func date(fromDayKey day: String) -> Date? { dayKeyParser.date(from: day) }

    private func dayLabel(_ day: String) -> String {
        Self.date(fromDayKey: day)?.formatted(date: .abbreviated, time: .omitted) ?? day
    }
}
