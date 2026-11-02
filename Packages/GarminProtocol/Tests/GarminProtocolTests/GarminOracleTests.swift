import XCTest
@testable import GarminProtocol

/// The Swift side of the parity oracle: `garminOracleLines()` must reproduce, line for line, the literal
/// pasted into the Kotlin test. That literal is this function's standalone stdout, so a byte that changes
/// on either platform without the other fails here and in `GarminOracleTest.kt`.
final class GarminOracleTests: XCTestCase {

    private func kotlinOracle() throws -> [String] {
        let relative = "android/app/src/test/java/com/noop/garmin/GarminOracleTest.kt"
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                let source = try String(contentsOf: candidate, encoding: .utf8)
                let begin = try XCTUnwrap(source.range(of: "ORACLE = \"\"\"\n"))
                let end = try XCTUnwrap(source.range(of: "\n\"\"\"", range: begin.upperBound..<source.endIndex))
                return source[begin.upperBound..<end.lowerBound].components(separatedBy: "\n")
            }
            directory = directory.deletingLastPathComponent()
        }
        throw XCTSkip("Kotlin oracle not found (package checked out without the android tree)")
    }

    func testSwiftMatchesTheKotlinOracleLiteral() throws {
        let expected = try kotlinOracle()
        let actual = garminOracleLines()
        XCTAssertEqual(actual.count, expected.count)
        for (index, (a, e)) in zip(actual, expected).enumerated() where a != e {
            XCTFail("oracle line \(index + 1)\n  swift:  \(a)\n  kotlin: \(e)")
        }
    }
}
