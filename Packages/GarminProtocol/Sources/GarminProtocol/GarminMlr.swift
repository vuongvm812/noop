import Foundation

// MARK: - Multi-link reliable (MLR): header packing and the sliding-window state machine
//
// Wire facts (Gadgetbridge docs + source, see ATTRIBUTION.md). A handle registered with `reliable` carries
// a 2-byte header before each fragment:
//   byte0 = 0x80 | (handle & 7) << 4 | (reqNum >> 2) & 0x0F
//   byte1 = (reqNum & 3) << 6 | seq & 0x3F
// `seq` numbers this side's data packets mod 64; `reqNum` is the cumulative ack: the next seq this side
// expects from the peer. A 2-byte packet with no data is a pure ack. At most 32 packets are in flight.
// Retransmission starts at 1 s and doubles to 20 s, halving the window each time; an ack restores both.
// Received data is acked after 5 packets or 20 ms, whichever first.
//
// The session below is a pure state machine: the caller passes the current time in milliseconds and
// sends whatever packets come back, and calls `tick` at `nextDeadlineMs`. No timers or threads live here,
// which is what lets both platforms replay one scripted trace byte-for-byte.
//
// Two deliberate differences from Gadgetbridge, both safe by construction:
//   - a retransmitted packet carries the CURRENT reqNum, not the one it was first sent with. Acks are
//     cumulative, so the current value is always correct, and a stale one could read as a huge ack
//     after the peer's counter wrapped;
//   - every data packet carries an ack, so sending one clears the pending ack timer instead of also
//     sending a pure ack 20 ms later (fewer radio wake-ups, the battery lever this layer has).

public struct GarminMlrHeader: Equatable, Sendable {
    public let handle: UInt8   // low 3 bits of the registered handle
    public let reqNum: UInt8   // 0…63
    public let seq: UInt8      // 0…63

    public init(handle: UInt8, reqNum: UInt8, seq: UInt8) {
        self.handle = handle & 0x07
        self.reqNum = reqNum & 0x3F
        self.seq = seq & 0x3F
    }

    public var bytes: [UInt8] {
        [0x80 | (handle & 0x07) << 4 | (reqNum >> 2) & 0x0F,
         (reqNum & 0x03) << 6 | (seq & 0x3F)]
    }

    /// Unpack the first two bytes of a packet, or nil if it is shorter or bit 7 of byte 0 is clear.
    public static func parse(_ packet: [UInt8]) -> GarminMlrHeader? {
        guard packet.count >= 2, packet[0] & 0x80 != 0 else { return nil }
        let b0 = packet[0], b1 = packet[1]
        return GarminMlrHeader(handle: (b0 & 0x70) >> 4,
                               reqNum: (b0 & 0x0F) << 2 | (b1 >> 6) & 0x03,
                               seq: b1 & 0x3F)
    }
}

public struct GarminMlrSession {

    public static let sequenceModulus = 64
    public static let maxInFlight = 32
    public static let initialRetransmitMs: Int64 = 1_000
    public static let maxRetransmitMs: Int64 = 20_000
    public static let ackDelayMs: Int64 = 20
    public static let ackThreshold = 5

    /// The handle value the watch registered (bit 7 typically set); only its low 3 bits go on the wire.
    public let handle: UInt8
    private var payloadSize: Int

    private var nextSendSeq = 0
    private var lastRcvAck = 0     // oldest of our packets the peer has not acked
    private var nextRcvSeq = 0     // next seq we expect from the peer (our reqNum)
    private var lastSendAck = 0    // reqNum we last told the peer
    private var window = GarminMlrSession.maxInFlight
    private var retransmitMs = GarminMlrSession.initialRetransmitMs
    private var queue: [[UInt8]] = []
    private var sent: [[UInt8]?] = Array(repeating: nil, count: GarminMlrSession.sequenceModulus)
    private var ackDeadline: Int64?
    private var retransmitDeadline: Int64?

    /// - Parameter mtu: the negotiated ATT MTU. Each packet holds MTU − 3 bytes, two of them header.
    public init(handle: UInt8, mtu: Int) {
        self.handle = handle
        self.payloadSize = GarminMlrSession.payloadSize(mtu: mtu)
    }

    public static func payloadSize(mtu: Int) -> Int { max(1, mtu - 5) }

    public mutating func setMtu(_ mtu: Int) { payloadSize = GarminMlrSession.payloadSize(mtu: mtu) }

    /// Packets sent and not yet acked.
    public var inFlight: Int { GarminMlrSession.distance(from: lastRcvAck, to: nextSendSeq) }

    /// Fragments waiting for window space.
    public var queued: Int { queue.count }

    /// The current send window (32, halved by each retransmission timeout, regrown by acks).
    public var windowSize: Int { window }

    /// The current retransmission timeout.
    public var retransmitTimeoutMs: Int64 { retransmitMs }

    /// When `tick` next has work to do, or nil when idle.
    public var nextDeadlineMs: Int64? {
        switch (ackDeadline, retransmitDeadline) {
        case let (a?, r?): return min(a, r)
        case let (a?, nil): return a
        case let (nil, r?): return r
        case (nil, nil): return nil
        }
    }

    /// Queue an outbound frame; returns the packets to write now (the rest wait for window space).
    public mutating func send(_ frame: GarminOutboundFrame, nowMs: Int64) -> [[UInt8]] {
        let bytes = frame.bytes
        var start = 0
        while start < bytes.count {
            let end = min(start + payloadSize, bytes.count)
            queue.append(Array(bytes[start..<end]))
            start = end
        }
        return pump(nowMs: nowMs)
    }

    public struct Received: Equatable {
        /// In-order payload to hand to the COBS stream decoder, if this packet carried new data.
        public let data: [UInt8]?
        /// Packets to write now (an immediate ack and/or newly unblocked data).
        public let outbound: [[UInt8]]
    }

    /// Process one inbound packet for this handle (header included).
    public mutating func receive(_ packet: [UInt8], nowMs: Int64) -> Received {
        guard let header = GarminMlrHeader.parse(packet), header.handle == handle & 0x07 else {
            return Received(data: nil, outbound: [])
        }
        var out: [[UInt8]] = []
        let reqNum = Int(header.reqNum)
        if reqNum != lastRcvAck { processAck(reqNum, nowMs: nowMs) }

        var data: [UInt8]?
        if packet.count > 2 {
            if Int(header.seq) == nextRcvSeq {
                nextRcvSeq = (nextRcvSeq + 1) % GarminMlrSession.sequenceModulus
                data = Array(packet[2...])
                if GarminMlrSession.distance(from: lastSendAck, to: nextRcvSeq) >= GarminMlrSession.ackThreshold {
                    out.append(ackPacket())
                } else {
                    ackDeadline = nowMs + GarminMlrSession.ackDelayMs
                }
            } else {
                // Out of order or a duplicate: the peer retransmits from what we ack, so re-state it now.
                out.append(ackPacket())
            }
        }
        out += pump(nowMs: nowMs)
        return Received(data: data, outbound: out)
    }

    /// Fire any due timer; returns the packets to write.
    public mutating func tick(nowMs: Int64) -> [[UInt8]] {
        var out: [[UInt8]] = []
        if let due = ackDeadline, due <= nowMs {
            out.append(ackPacket())
        }
        if let due = retransmitDeadline, due <= nowMs {
            retransmitMs = min(retransmitMs * 2, GarminMlrSession.maxRetransmitMs)
            window = max(1, window / 2)
            var seq = lastRcvAck
            while seq != nextSendSeq {
                if let data = sent[seq] {
                    out.append(packet(seq: seq, data: data))
                }
                seq = (seq + 1) % GarminMlrSession.sequenceModulus
            }
            retransmitDeadline = nowMs + retransmitMs
        }
        return out
    }

    // MARK: Internals

    private static func distance(from a: Int, to b: Int) -> Int {
        (b - a + sequenceModulus) % sequenceModulus
    }

    private mutating func processAck(_ reqNum: Int, nowMs: Int64) {
        let acked = GarminMlrSession.distance(from: lastRcvAck, to: reqNum)
        // An ack beyond what we sent is corrupt or stale; ignore it rather than expire unsent slots.
        guard acked <= inFlight else { return }
        var seq = lastRcvAck
        while seq != reqNum {
            sent[seq] = nil
            seq = (seq + 1) % GarminMlrSession.sequenceModulus
        }
        lastRcvAck = reqNum
        retransmitMs = GarminMlrSession.initialRetransmitMs
        window = min(window + acked, GarminMlrSession.maxInFlight)
        retransmitDeadline = lastRcvAck == nextSendSeq ? nil : nowMs + retransmitMs
    }

    private mutating func pump(nowMs: Int64) -> [[UInt8]] {
        var out: [[UInt8]] = []
        while !queue.isEmpty && inFlight < window {
            let wasIdle = inFlight == 0
            let data = queue.removeFirst()
            sent[nextSendSeq] = data
            out.append(packet(seq: nextSendSeq, data: data))
            nextSendSeq = (nextSendSeq + 1) % GarminMlrSession.sequenceModulus
            if wasIdle { retransmitDeadline = nowMs + retransmitMs }
        }
        return out
    }

    /// A data packet; it also carries (and so discharges) the current ack.
    private mutating func packet(seq: Int, data: [UInt8]) -> [UInt8] {
        lastSendAck = nextRcvSeq
        ackDeadline = nil
        return GarminMlrHeader(handle: handle, reqNum: UInt8(nextRcvSeq), seq: UInt8(seq)).bytes + data
    }

    private mutating func ackPacket() -> [UInt8] {
        lastSendAck = nextRcvSeq
        ackDeadline = nil
        return GarminMlrHeader(handle: handle, reqNum: UInt8(nextRcvSeq), seq: 0).bytes
    }
}
