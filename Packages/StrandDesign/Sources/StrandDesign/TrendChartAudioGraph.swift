#if !os(watchOS)
// The audio-graph descriptor rides on TrendChart, which the watch never shows; excluded there with it.
import SwiftUI
import Accessibility

// MARK: - TrendChart audio graph (#58)
//
// VoiceOver's Audio Graph and its chart explorer read a chart through an `AXChartDescriptor`: one data
// series the user can step through point by point, or hear as a rising and falling tone. TrendChart
// collapses its marks into one summary element, so without a descriptor a VoiceOver user gets the
// summary but no individual reading. The descriptor voices every point with the chart's own
// `valueFormat` and `dateFormat`, so the spoken values match the callout a pointer or finger shows.

/// The `AXChartDescriptor` for a `TrendChart`'s full-resolution points.
struct TrendChartAudioGraph: AXChartDescriptorRepresentable {
    let title: String
    let summary: String
    let points: [TrendPoint]
    let valueRange: ClosedRange<Double>
    let valueFormat: (Double) -> String
    let dateFormat: (Date) -> String

    func makeChartDescriptor() -> AXChartDescriptor {
        let times = points.map { $0.date.timeIntervalSince1970 }
        let lo = times.first ?? 0
        let hi = max(times.last ?? lo, lo + 1)
        let dateFormat = self.dateFormat
        let valueFormat = self.valueFormat
        let xAxis = AXNumericDataAxisDescriptor(
            title: String(localized: "Date", bundle: .module),
            range: lo...hi,
            gridlinePositions: [],
            valueDescriptionProvider: { dateFormat(Date(timeIntervalSince1970: $0)) }
        )
        let values = points.map(\.value)
        let yLo = min(valueRange.lowerBound, values.min() ?? valueRange.lowerBound)
        let yHi = max(valueRange.upperBound, values.max() ?? valueRange.upperBound)
        let yAxis = AXNumericDataAxisDescriptor(
            title: String(localized: "Value", bundle: .module),
            range: yLo...max(yHi, yLo + 1),
            gridlinePositions: [],
            valueDescriptionProvider: { valueFormat($0) }
        )
        let series = AXDataSeriesDescriptor(
            name: title,
            isContinuous: true,
            dataPoints: points.map { AXDataPoint(x: $0.date.timeIntervalSince1970, y: $0.value) }
        )
        return AXChartDescriptor(
            title: title,
            summary: summary,
            xAxis: xAxis,
            yAxis: yAxis,
            additionalAxes: [],
            series: [series]
        )
    }
}
#endif
