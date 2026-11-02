import SwiftUI
import StrandDesign

// MARK: - Supplements card (Today screen) — #33
//
// Logs today's supplement intake in place on Today. The rows are the Journal catalog's Supplements-group
// questions and the answers are ordinary native journal entries (`Repository.journalDeviceId`), written
// through the same Repository calls the Journal uses, so What Moves You and the correlations read them
// unchanged. The Journal log card leaves the Supplements group out while answering; supplement questions
// are still added and edited in the Journal's edit mode. Today only (the call sites gate on the selected
// day). Twin of Android `SupplementsTodayCard` (android/.../ui/SupplementsToday.kt).

struct SupplementsTodayCard: View {

    @EnvironmentObject var repo: Repository
    @EnvironmentObject var router: NavRouter

    /// The Supplements-group rows. nil = still loading → render nothing (no empty-state flash).
    @State private var items: [JournalCatalogItem]?
    /// question → answeredYes for today, native rows only.
    @State private var answers: [String: Bool] = [:]
    /// question → numeric value for today, native rows only.
    @State private var numeric: [String: Double] = [:]

    private var todayKey: String { Repository.localDayKey(Date()) }

    var body: some View {
        Group {
            if let items {
                card(items)
            }
        }
        // A sync bumps refreshSeq and a new day changes the key; either re-reads catalog and answers.
        .task(id: SupplementsLoadKey(seq: repo.refreshSeq, day: todayKey)) { await reload() }
        // Catalog edits made in the Journal (add / rename / regroup / remove) land in UserDefaults. Re-resolve
        // the rows from the catalog only (no database read), so the card follows them without a sync.
        .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
            let next = JournalCatalogStore().supplementItems()
            if items != nil, next != items { items = next }
        }
    }

    private func card(_ items: [JournalCatalogItem]) -> some View {
        NoopCard(tint: StrandPalette.restColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack(spacing: NoopMetrics.space2) {
                    Image(systemName: "pills")
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.restColor)
                        .accessibilityHidden(true)
                    Text("Supplements")
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.textPrimary)
                    Spacer()
                }
                if items.isEmpty {
                    emptyState
                } else {
                    ForEach(items) { item in row(item) }
                }
            }
        }
    }

    private func row(_ item: JournalCatalogItem) -> some View {
        HStack {
            Text(verbatim: item.display)   // display = rename ?? canonical; data, not a UI literal
                .font(StrandFont.body)
                .foregroundStyle(StrandPalette.textPrimary)
            Spacer()
            if item.kind.isNumeric {
                JournalNumericControl(
                    item: item,
                    value: numeric[item.canonical],
                    onCommit: { value in
                        write { await repo.saveJournalNumeric(day: todayKey, question: item.canonical, value: value) }
                    },
                    onClear: {
                        write { await repo.clearJournalAnswer(day: todayKey, question: item.canonical) }
                    })
            } else {
                JournalAnswerPills(answer: answers[item.canonical]) { answer in
                    write {
                        if let answer {
                            await repo.saveJournalAnswer(day: todayKey, question: item.canonical, answeredYes: answer)
                        } else {
                            await repo.clearJournalAnswer(day: todayKey, question: item.canonical)
                        }
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            Text("No supplement questions yet.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textSecondary)
            Button { router.openJournal() } label: {
                Text("Add one in the Journal")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.accent)
            }
            .buttonStyle(.plain)
        }
    }

    /// Run one journal write, then re-read today's answers. Also drops the Insights load marker so the
    /// Journal and the effect cards re-read instead of restoring a snapshot taken before this write.
    private func write(_ op: @escaping () async -> Void) {
        Task {
            await op()
            repo.insightsLoadedSeq = -1
            await reload()
        }
    }

    private func reload() async {
        let day = todayKey
        let rows = JournalCatalogStore().supplementItems()
        let a = await repo.nativeJournalAnswers(day: day)
        let n = await repo.nativeJournalNumeric(day: day)
        answers = a
        numeric = n
        items = rows
    }
}

/// Reload key: a sync (seq) or a day rollover re-reads the card.
private struct SupplementsLoadKey: Equatable {
    let seq: Int
    let day: String
}
