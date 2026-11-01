// Standalone parity-oracle entry point; see Tests/GarminProtocolTests/GarminOracleCases.swift for the build line.
// With the argument `session` it prints the session oracle (GarminSessionOracleCases.swift), with `sync`
// the history-sync oracle (GarminSyncOracleCases.swift), and with `realtime` the live-channel decoder and
// intake oracle (GarminRealtimeOracleCases.swift).
let args = CommandLine.arguments
let lines = args.contains("session") ? garminSessionOracleLines()
    : args.contains("sync") ? garminSyncOracleLines()
    : args.contains("realtime") ? garminRealtimeOracleLines() : garminOracleLines()
for line in lines { print(line) }
