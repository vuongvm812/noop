import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - What affects your HRV and sleep (Today card)
//
// The top three significant effects on HRV and Rest from the What Moves You ranking, journal habits
// and automatic factors alike ("Late workout → HRV −8% next morning"), each with its confidence tier.
// Reads the SAME `WhatMovesYouLoader` snapshot the Insights hub shows, so the two cannot disagree;
// tapping opens the hub. Twin of Android `WhatAffectsTodayCard` (android/.../ui/WhatAffectsTodayCard.kt).

struct WhatAffectsTodayCard: View {

    @EnvironmentObject var repo: Repository
    @EnvironmentObject var router: NavRouter
    @ObservedObject private var loader = WhatMovesYouLoader.shared

    var body: some View {
        Button { router.openInsightsHub() } label: {
            NoopCard(tint: StrandPalette.accent) {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    HStack(spacing: NoopMetrics.space2) {
                        Image(systemName: "sparkle.magnifyingglass")
                            .font(.system(size: 18))
                            .foregroundStyle(StrandPalette.accent)
                            .accessibilityHidden(true)
                        Text(String(localized: "What affects your HRV and sleep"))
                            .font(StrandFont.headline)
                            .foregroundStyle(StrandPalette.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(StrandPalette.textTertiary)
                            .accessibilityHidden(true)
                    }
                    content
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(Text(String(localized: "Open What Moves You")))
        .task(id: WhatMovesYouLoader.loadKey(repo)) { await loader.load(repo: repo) }
    }

    @ViewBuilder
    private var content: some View {
        if let snap = loader.snapshot {
            let rows = WhatMovesYouLoader.highlights(snap)
            if rows.isEmpty {
                Text(snap.sleepOutcomeDays < WhatMovesYouHighlights.minHistoryDays()
                     ? String(localized: "Needs ~2 weeks of data")
                     : String(localized: "No clear patterns yet. Keep wearing your strap and logging your journal."))
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    ForEach(rows.indices, id: \.self) { i in
                        HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space2) {
                            Text(InsightsSentences.todayLine(rows[i]))
                                .font(StrandFont.subhead)
                                .foregroundStyle(StrandPalette.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: NoopMetrics.space2)
                            ScoreStatePill(InsightsHubView.scoreState(rows[i].confidence))
                        }
                    }
                }
            }
        } else {
            Text(String(localized: "Reading your journal and outcomes…"))
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
        }
    }
}
