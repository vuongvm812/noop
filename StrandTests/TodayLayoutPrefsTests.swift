import XCTest
@testable import Strand

/// Twin of the Android `TodayLayoutPrefsTest` (#today-layout): default order, encode/decode round-trip,
/// reorder, and the never-hide "insert missing section at its default position" invariant — pinned on both
/// platforms so the byte-identical "today.sectionOrder" wire format can't drift.
final class TodayLayoutPrefsTests: XCTestCase {

    func testEmptyOrUnsetYieldsDefaultOrder() {
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder(""), TodaySection.defaultOrder)
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder("   "), TodaySection.defaultOrder)
    }

    func testEncodeDecodeRoundTripsAReorderedList() {
        let reordered: [TodaySection] = [
            .heartRate, .hero, .yourCards, .liveSession, .synthesis, .keyMetrics, .workouts, .recoveryVitals,
            .journal, .menstrualCycle, .supplements, .addedCards,
        ]
        let encoded = TodayLayoutPrefs.encode(reordered)
        XCTAssertEqual(encoded, "heartRate,hero,yourCards,liveSession,synthesis,keyMetrics,workouts,recoveryVitals,journal,menstrualCycle,supplements,addedCards")
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder(encoded), reordered)
    }

    /// The v1 upgrade path: an order saved by the FIRST cut (6 sections — no hero/liveSession, which were
    /// pinned then) must surface the two new sections at the TOP (their default position), not teleport
    /// them to the bottom of the user's saved order.
    func testSavedOrderFromFirstCutInsertsHeroAndSessionAtTheirDefaultPosition() {
        let firstCut = "synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards"
        XCTAssertEqual(
            TodayLayoutPrefs.decodeOrder(firstCut),
            // journal(8) follows everything saved → appended; addedCards(10) is last, appended after it.
            [.hero, .liveSession, .synthesis, .keyMetrics, .workouts, .heartRate, .recoveryVitals, .yourCards, .menstrualCycle, .journal, .supplements, .addedCards]
        )
    }

    func testInsertsAnyMissingSectionAtItsDefaultPositionRelativeToSaved() {
        let partial = "heartRate,synthesis,keyMetrics,recoveryVitals"
        XCTAssertEqual(
            TodayLayoutPrefs.decodeOrder(partial),
            [.hero, .liveSession, .workouts, .heartRate, .synthesis, .keyMetrics, .recoveryVitals, .yourCards, .menstrualCycle, .journal, .supplements, .addedCards]
        )
    }

    func testDropsUnknownTokensAndCollapsesDuplicates() {
        let messy = "yourCards,BOGUS,yourCards,heartRate, ,heartRate"
        XCTAssertEqual(
            TodayLayoutPrefs.decodeOrder(messy),
            [.hero, .liveSession, .synthesis, .keyMetrics, .workouts, .recoveryVitals, .yourCards, .heartRate, .menstrualCycle, .journal, .supplements, .addedCards]
        )
    }

    func testAllJunkYieldsDefaultOrder() {
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder("nope,,zzz"), TodaySection.defaultOrder)
    }

    func testHiddenSectionsAreExplicitReversibleAndDeduplicated() {
        let hidden = TodayLayoutPrefs.decodeHidden("workouts,BOGUS,workouts,journal")
        XCTAssertEqual(hidden, [.workouts, .journal])
        XCTAssertEqual(TodayLayoutPrefs.encodeHidden(hidden), "workouts,journal")
    }

    func testVisibleOrderFiltersHiddenWithoutChangingSavedOrder() {
        let order = "heartRate,hero,yourCards,liveSession,synthesis,keyMetrics,workouts,recoveryVitals,journal"
        XCTAssertEqual(
            TodayLayoutPrefs.visibleOrder(orderRaw: order, hiddenRaw: "hero,workouts"),
            [.heartRate, .yourCards, .liveSession, .synthesis, .keyMetrics, .recoveryVitals, .menstrualCycle, .journal, .supplements, .addedCards]
        )
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder(order), [
            .heartRate, .hero, .yourCards, .liveSession, .synthesis, .keyMetrics, .workouts,
            .recoveryVitals, .menstrualCycle, .journal, .supplements, .addedCards,
        ])
    }

    func testNewOrPreviouslyMissingSectionsDefaultToVisible() {
        XCTAssertTrue(
            TodayLayoutPrefs.visibleOrder(
                orderRaw: "synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards",
                hiddenRaw: "workouts"
            ).contains(.journal)
        )
    }

    /// defaultOrder must cover EVERY case: the never-hide merge iterates it, so a case missing from the
    /// default order could otherwise be dropped from render (Android) or mis-sorted (iOS).
    func testDefaultOrderCoversEveryCase() {
        XCTAssertEqual(Set(TodaySection.defaultOrder), Set(TodaySection.allCases))
        XCTAssertEqual(TodaySection.defaultOrder.count, TodaySection.allCases.count)
    }

    func testSectionRawKeysAreStableAndUnique() {
        let raws = TodaySection.allCases.map(\.rawValue)
        XCTAssertEqual(raws.count, Set(raws).count, "raw keys must be unique (they're the persisted identity)")
        // Pin the exact wire strings — they must match the Android TodaySection byte-for-byte.
        XCTAssertEqual(
            raws,
            ["hero", "liveSession", "synthesis", "keyMetrics", "workouts", "heartRate", "recoveryVitals", "yourCards", "menstrualCycle", "journal", "supplements", "addedCards"]
        )
    }

    /// #33: a layout saved before the Supplements section existed surfaces it right after the journal
    /// widget (its default slot), visible, without disturbing the rest of the saved order.
    func testSupplementsInsertsAfterJournalForASavedOrderThatPredatesIt() {
        let saved = "journal,hero,liveSession,synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards,menstrualCycle,addedCards"
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder(saved), [
            .journal, .hero, .liveSession, .synthesis, .keyMetrics, .workouts, .heartRate, .recoveryVitals,
            .yourCards, .menstrualCycle, .supplements, .addedCards,
        ])
        XCTAssertTrue(TodayLayoutPrefs.visibleOrder(orderRaw: saved, hiddenRaw: "journal").contains(.supplements))
        XCTAssertEqual(TodayLayoutPrefs.decodeHidden("supplements"), [.supplements])
    }

    func testEditableLayoutHidesAndRestoresWithoutDeleting() {
        var draft = EditableLayoutDraft(
            visible: TodaySection.defaultOrder,
            allItems: TodaySection.defaultOrder
        )

        draft.hide(.workouts)
        XCTAssertFalse(draft.visible.contains(.workouts))
        XCTAssertEqual(draft.hidden, [.workouts])

        draft.show(.workouts)
        XCTAssertEqual(draft.visible.last, .workouts)
        XCTAssertTrue(draft.hidden.isEmpty)
        XCTAssertEqual(Set(draft.visible), Set(TodaySection.defaultOrder))
    }

    func testEditableLayoutKeepsAtLeastOneItemVisible() {
        var draft = EditableLayoutDraft(visible: [KeyMetric.hrv], hidden: KeyMetric.defaultOrder.filter { $0 != .hrv })
        draft.hide(.hrv)
        XCTAssertEqual(draft.visible, [.hrv])
    }

    /// #57: the #42 `stress` section is retired (its value moved to a Key Metrics tile). A saved order or
    /// hidden set that still carries it decodes as if it were absent, and `stripRetired` removes it.
    /// Twin of the Kotlin `retiredStressSection_isDroppedAndStripped`.
    func testRetiredStressSectionIsDroppedAndStripped() {
        XCTAssertNil(TodaySection(rawValue: "stress"))
        let saved = "hero,liveSession,synthesis,keyMetrics,workouts,heartRate,stress,recoveryVitals,yourCards,menstrualCycle,journal,supplements,addedCards"
        XCTAssertEqual(TodayLayoutPrefs.decodeOrder(saved), TodaySection.defaultOrder)
        XCTAssertEqual(TodayLayoutPrefs.decodeHidden("stress,journal"), [.journal])
        XCTAssertEqual(TodayLayoutPrefs.stripRetired(saved),
                       "hero,liveSession,synthesis,keyMetrics,workouts,heartRate,recoveryVitals,yourCards,menstrualCycle,journal,supplements,addedCards")
        XCTAssertEqual(TodayLayoutPrefs.stripRetired("stress"), "")
        XCTAssertEqual(TodayLayoutPrefs.stripRetired("journal, stress ,hero"), "journal,hero")
    }

    /// #57: a customised Key Metrics layout gains the Stress tile (unless the old Stress card was hidden)
    /// and VO₂max, once. The default layout is untouched because it already lists both.
    /// Twin of the Kotlin `keyMetricsMigration_addsStressAndVo2`.
    func testKeyMetricsMigrationAddsStressAndVo2() {
        XCTAssertNil(KeyMetricPrefs.migratedForStressVo2(keyMetricsRaw: "", hiddenSectionsRaw: ""))
        XCTAssertEqual(KeyMetricPrefs.migratedForStressVo2(keyMetricsRaw: "charge,hrv", hiddenSectionsRaw: ""),
                       "charge,hrv,stress,vo2max")
        XCTAssertEqual(KeyMetricPrefs.migratedForStressVo2(keyMetricsRaw: "charge,hrv", hiddenSectionsRaw: "journal,stress"),
                       "charge,hrv,vo2max")
        XCTAssertNil(KeyMetricPrefs.migratedForStressVo2(keyMetricsRaw: "vo2max,stress", hiddenSectionsRaw: ""))
        XCTAssertNil(KeyMetricPrefs.migratedForStressVo2(keyMetricsRaw: "bogus", hiddenSectionsRaw: ""))
        XCTAssertEqual(KeyMetric.defaultOrder.suffix(2), [.stress, .vo2max])
        XCTAssertEqual(KeyMetric.stress.rawValue, "stress")
        XCTAssertEqual(KeyMetric.vo2max.rawValue, "vo2max")
    }

    func testKeyMetricsMigrationRunsOnceAndCleansTheTodayLayout() {
        let suite = "TodayLayoutPrefsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("charge,steps", forKey: KeyMetricPrefs.layoutKey)
        defaults.set("hero,heartRate,stress,journal", forKey: TodayLayoutPrefs.orderKey)
        defaults.set("stress,journal", forKey: TodayLayoutPrefs.hiddenKey)
        KeyMetricPrefs.runMigrations(defaults)
        XCTAssertEqual(defaults.string(forKey: KeyMetricPrefs.layoutKey), "charge,steps,vo2max")
        XCTAssertEqual(defaults.string(forKey: TodayLayoutPrefs.orderKey), "hero,heartRate,journal")
        XCTAssertEqual(defaults.string(forKey: TodayLayoutPrefs.hiddenKey), "journal")
        // Once only: a tile the user removes afterwards is not put back.
        defaults.set("charge", forKey: KeyMetricPrefs.layoutKey)
        KeyMetricPrefs.runMigrations(defaults)
        XCTAssertEqual(defaults.string(forKey: KeyMetricPrefs.layoutKey), "charge")
    }
}
