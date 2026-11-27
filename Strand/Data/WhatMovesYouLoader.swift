import Foundation
import StrandAnalytics
import WhoopStore

// MARK: - What Moves You loader
//
// The ONE load behind both What Moves You readouts: the Insights hub's ranked feed and the Today
// "What affects your HRV and sleep" card. Both read `snapshot` from the shared instance, so they show
// the same rows from the same ranking (two readouts of one fact must not disagree).
//
// It gathers the journal (Yes / No days per question), the automatic factors from sensor data
// (AutoFactors: workouts, Effort, steps, bedtimes) and the four outcome series, then runs ONE
// `EffectRanker.rankFamily` over journal ∪ automatic behaviours, so both share a single
// Benjamini–Hochberg false-discovery family. Rest is read through `Repository.restScore(_:)`, the
// resolver every other Rest surface uses. Kotlin twin: `AppViewModel.loadWhatMovesYou`.

@MainActor
final class WhatMovesYouLoader: ObservableObject {

    static let shared = WhatMovesYouLoader()

    typealias Outcome = InsightsHubViewModel.Outcome

    struct Snapshot {
        /// Journal Yes days per question (the dose cards back-fill from these).
        let journalBehaviours: [String: Set<String>]
        /// Outcome series per metric key ("recovery" / "hrv" / "sleep_performance" / "rhr").
        let outcomeByKey: [String: [String: Double]]
        /// The ranked rows per outcome, from one family across every outcome and behaviour.
        let ranked: [Outcome: [RankedEffect]]
        /// Days that carry an HRV or Rest value (the Today card's "needs about two weeks" gate).
        let sleepOutcomeDays: Int
    }

    @Published private(set) var snapshot: Snapshot?

    /// What a snapshot reflects: the repository refresh and the native journal writes it was built from.
    struct LoadKey: Equatable {
        let refreshSeq: Int
        let journalSeq: Int
    }

    /// The key for the repository's current state. The Today card keys its `.task(id:)` on it, so a
    /// new sync or a journal answer logged since both trigger a load.
    static func loadKey(_ repo: Repository) -> LoadKey {
        LoadKey(refreshSeq: repo.refreshSeq, journalSeq: repo.journalSeq)
    }

    private var loadedKey: LoadKey?
    private var inFlight: (key: LoadKey, generation: Int, task: Task<Snapshot, Never>)?
    /// Bumped per started load; only the newest load publishes, so a slower older one cannot overwrite it.
    private var generation = 0

    /// Load and publish the snapshot. Without `force` it is skipped while the snapshot already reflects
    /// the current repository refresh and journal writes, and concurrent callers for that state await
    /// the same load; the hub passes `force` so it always reads afresh on entry.
    func load(repo: Repository, force: Bool = false) async {
        let key = Self.loadKey(repo)
        if !force {
            if loadedKey == key, snapshot != nil { return }
            if let running = inFlight, running.key == key {
                _ = await running.task.value
                return
            }
        }
        generation += 1
        let gen = generation
        let task = Task { await Self.build(repo: repo) }
        inFlight = (key, gen, task)
        let snap = await task.value
        guard gen == generation else { return }
        inFlight = nil
        snapshot = snap
        loadedKey = key
    }

    /// The Today card's rows: the strongest significant HRV and Rest effects from the shared ranking.
    static func highlights(_ snap: Snapshot) -> [RankedEffect] {
        let outcomes: [Outcome] = [.hrv, .sleep]
        var byLabel: [String: [RankedEffect]] = [:]
        for o in outcomes { byLabel[o.outcomeName] = snap.ranked[o] ?? [] }
        return WhatMovesYouHighlights.top(byLabel, outcomes: outcomes.map(\.outcomeName))
    }

    // MARK: Build

    /// Reads the repository on the main actor (Repository is @MainActor) into plain values, then shapes
    /// and ranks them off the main actor.
    private static func build(repo: Repository) async -> Snapshot {
        let journal = await repo.journalEntries().map { JournalRow(day: $0.day, question: $0.question, yes: $0.answeredYes) }
        var series: [String: [(day: String, value: Double)]] = [:]
        // The imported metricSeries keys with a DailyMetric column fallback; Rest comes from `restScore`.
        for key in ["recovery", "hrv", "rhr"] { series[key] = await repo.series(key: key, source: Repository.whoopSource) }
        // Rest goes through the one resolver every Rest surface reads; it reads the repository's caches.
        let days = repo.days.map {
            DayRow(day: $0.day, effort: $0.strain, steps: $0.steps, recovery: $0.recovery,
                   hrv: $0.avgHrv, rhr: $0.restingHr.map(Double.init), rest: repo.restScore($0))
        }
        let workouts = await repo.dedupedWorkouts().map { Span(startTs: $0.startTs, endTs: $0.endTs) }
        let sleeps = await repo.allSleepSessions().map { Span(startTs: $0.effectiveStartTs, endTs: $0.endTs) }
        let outcomeKeys = Outcome.allCases.map { (name: $0.outcomeName, key: $0.key) }
        let inputs = Inputs(journal: journal, series: series, days: days, workouts: workouts, sleeps: sleeps,
                            outcomeKeys: outcomeKeys, today: Repository.dayString(Date()))

        let shaped = await Task.detached(priority: .userInitiated) { Self.shape(inputs) }.value
        var ranked: [Outcome: [RankedEffect]] = [:]
        for o in Outcome.allCases { ranked[o] = shaped.family[o.outcomeName] ?? [] }
        return Snapshot(journalBehaviours: shaped.journalYes, outcomeByKey: shaped.outcomeByKey, ranked: ranked,
                        sleepOutcomeDays: shaped.sleepOutcomeDays)
    }

    /// One journal answer.
    private struct JournalRow: Sendable {
        let day: String
        let question: String
        let yes: Bool
    }

    /// One merged day: the AutoFactors inputs and the outcome fallbacks.
    private struct DayRow: Sendable {
        let day: String
        let effort: Double?
        let steps: Int?
        let recovery: Double?
        let hrv: Double?
        let rhr: Double?
        let rest: Double?
    }

    /// A workout or sleep span in unix seconds.
    private struct Span: Sendable {
        let startTs: Int
        let endTs: Int
    }

    /// Everything the build read from the repository, as plain values.
    private struct Inputs: Sendable {
        let journal: [JournalRow]
        let series: [String: [(day: String, value: Double)]]
        let days: [DayRow]
        let workouts: [Span]
        let sleeps: [Span]
        let outcomeKeys: [(name: String, key: String)]
        let today: String
    }

    /// What the detached shaping hands back.
    private struct Shaped: Sendable {
        let journalYes: [String: Set<String>]
        let outcomeByKey: [String: [String: Double]]
        let family: [String: [RankedEffect]]
        let sleepOutcomeDays: Int
    }

    /// Journal bucketing, outcome series, AutoFactors and the one ranked family. Pure; runs detached.
    nonisolated private static func shape(_ inputs: Inputs) -> Shaped {
        // Journal → Yes days and No days per question, kept apart; an unanswered day is in neither.
        var yes: [String: Set<String>] = [:]
        var no: [String: Set<String>] = [:]
        for e in inputs.journal {
            if e.yes { yes[e.question, default: []].insert(e.day) }
            else { no[e.question, default: []].insert(e.day) }
        }

        // Outcome series: imported metricSeries ∪ the DailyMetric column fallback for Charge / HRV / RHR;
        // Rest as the resolver returned it.
        var byKey: [String: [String: Double]] = [:]
        for (key, rows) in inputs.series {
            var dict: [String: Double] = [:]
            for row in rows { dict[row.day] = row.value }
            for d in inputs.days where dict[d.day] == nil {
                if let v = dailyOutcome(key: key, day: d) { dict[d.day] = v }
            }
            byKey[key] = dict
        }
        var rest: [String: Double] = [:]
        for d in inputs.days { if let v = d.rest { rest[d.day] = v } }
        byKey["sleep_performance"] = rest

        // Automatic factors from sensor data, each timestamp with its own local offset.
        let tz = TimeZone.current
        let workouts = inputs.workouts.map {
            AutoFactors.Workout(startTs: $0.startTs, endTs: $0.endTs, offsetSec: offsetSec(tz, $0.startTs))
        }
        let sleeps = inputs.sleeps.map {
            AutoFactors.Sleep(startTs: $0.startTs, endTs: $0.endTs, offsetSec: offsetSec(tz, $0.startTs))
        }
        let days = inputs.days.map { AutoFactors.Day(day: $0.day, effort: $0.effort, steps: $0.steps) }
        let auto = AutoFactors.factors(workouts: workouts, sleeps: sleeps, days: days, today: inputs.today)

        var behaviors = yes, controls = no
        behaviors.merge(auto.behaviors) { cur, _ in cur }
        controls.merge(auto.controls) { cur, _ in cur }
        var outcomes: [String: [String: Double]] = [:]
        for o in inputs.outcomeKeys { outcomes[o.name] = byKey[o.key] ?? [:] }
        // ONE family across every outcome the feed can show and every behaviour (journal ∪ automatic).
        // An automatic factor's day is the day it happened, so its lag 0 would compare it with the
        // morning BEFORE it; it is tested at lags 1 and 2 only.
        let family = EffectRanker.rankFamilyExcludingSameDay(behaviors: behaviors, controls: controls,
                                                             outcomes: outcomes,
                                                             noSameDay: Set(auto.behaviors.keys))

        let sleepDays = Set((byKey["hrv"] ?? [:]).keys).union((byKey["sleep_performance"] ?? [:]).keys).count
        return Shaped(journalYes: yes, outcomeByKey: byKey, family: family, sleepOutcomeDays: sleepDays)
    }

    /// The local UTC offset in effect at a unix timestamp.
    nonisolated private static func offsetSec(_ tz: TimeZone, _ ts: Int) -> Int {
        tz.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(ts)))
    }

    /// The merged DailyMetric column backing an outcome key (strap-only fallback).
    nonisolated private static func dailyOutcome(key: String, day d: DayRow) -> Double? {
        switch key {
        case "recovery": return d.recovery
        case "hrv":      return d.hrv
        case "rhr":      return d.rhr
        default:         return nil
        }
    }
}
