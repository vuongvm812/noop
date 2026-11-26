import SwiftUI
import StrandDesign
import StrandAnalytics

/// Intelligence's "Tomorrow's Charge" card and the save of the forecast it shows. Its own leaf so the
/// `Repository` / `ProfileStore` / `BehaviorStore` observation re-renders this card alone, never the parent
/// `IntelligenceView` body with its lazy By-Day list (the same leaf-scoping as `IntelSyncingNote`).
struct IntelligenceForecastSection: View {
    @EnvironmentObject var repo: Repository
    @EnvironmentObject var profile: ProfileStore
    @EnvironmentObject var behavior: BehaviorStore

    var body: some View {
        if let t = tonight {
            forecastCard(t.headline.forecast, bedtimeTs: t.anchorTs)
                // Same key and debounce as the Today card's save (`TomorrowForecastSaveKey`).
                .task(id: TomorrowForecastSaveKey(t)) {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    guard !Task.isCancelled else { return }
                    await repo.saveTomorrowForecast(t)
                }
        }
    }

    /// Tonight's forecast of tomorrow-morning Charge, from the SAME resolver as Today's Tomorrow's Charge
    /// card (`Repository.tomorrowForecast`): the real sleep need, the bedtime picked on Today (else the
    /// suggested one) and the strap alarm, wind-down or typical wake time. `nil` (and the card hidden) until
    /// there are enough scored nights to anchor honestly — never a fabricated number.
    private var tonight: RecoveryForecaster.Tonight? {
        repo.tomorrowForecast(age: profile.age > 0 ? profile.age : nil, behavior: behavior)
    }

    /// The forecast hero — tomorrow-morning Charge as the canonical liquid `LiquidVessel` gauge in the
    /// Charge tint, with the estimate counting up over it (the SAME hero language Sleep + Today use), on a
    /// frosted Charge-tinted card, with the plain-English estimate read-out beneath. A real forecast number,
    /// so it earns a liquid gauge. The number, ± band and copy are unchanged.
    private func forecastCard(_ f: RecoveryForecast, bedtimeTs: Double) -> some View {
        let frac = min(max(f.charge / 100.0, 0), 1)
        return VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            SectionHeader("Tomorrow's Charge", overline: "Evening forecast", trailing: String(localized: "Estimate"))
            NoopCard(padding: 20, tint: StrandPalette.chargeColor) {
                VStack(spacing: 14) {
                    // The signature liquid gauge: a filling vessel tinted to the forecast Charge, with the
                    // 0–100 estimate counting up over it and the ± band + state word beneath (Sleep's
                    // restHero idiom). Live so the fill actually flows on the hero surface.
                    ZStack {
                        LiquidVessel(value: frac, tint: StrandPalette.recoveryColor(f.charge), animated: true)
                            .frame(width: 184, height: 184)
                        VStack(spacing: 0) {
                            CountUpText(
                                value: f.charge,
                                format: { "\(Int($0.rounded()))" },
                                font: StrandFont.rounded(52),
                                color: StrandPalette.textPrimary
                            )
                            .shadow(color: .black.opacity(0.5), radius: 6, y: 1)
                            Text("± \(Int(f.band.rounded())) · \(StrandPalette.recoveryState(f.charge))")
                                .font(StrandFont.captionNumber)
                                .foregroundStyle(StrandPalette.textSecondary)
                        }
                        .allowsHitTesting(false)   // taps fall through to the vessel → splash
                    }
                    .padding(.top, 4)
                    .padding(.bottom, 6)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Tomorrow's Charge estimate \(Int(f.charge.rounded())) plus or minus \(Int(f.band.rounded()))")
                    VStack(alignment: .leading, spacing: 10) {
                        Text("You'll likely wake around \(Int(f.charge.rounded())) ± \(Int(f.band.rounded())) Charge if you sleep about \(sleepHoursLabel(f.plannedSleepHours)) tonight.")
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text("Estimate from today's effort, your sleep need, a \(AppClock.hourMinute(unix: Int(bedtimeTs))) bedtime and your \(f.nights)-night recovery baseline, not a measurement. Your real Charge is scored from tomorrow's HRV when you wake.")
                            .font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
    }

    /// "~7h" / "~7h 30m" for the planned-sleep assumption (hours rounded to the nearest 30 min).
    private func sleepHoursLabel(_ hours: Double) -> String {
        let half = (hours * 2).rounded() / 2
        let h = Int(half)
        let m = Int((half - Double(h)) * 60)
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }
}
