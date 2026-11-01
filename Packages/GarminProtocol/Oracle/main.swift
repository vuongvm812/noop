// Standalone parity-oracle entry point; see Tests/GarminProtocolTests/GarminOracleCases.swift for the build line.
// With the argument `session` it prints the session oracle (GarminSessionOracleCases.swift), with `sync`
// the history-sync oracle (GarminSyncOracleCases.swift).
let args = CommandLine.arguments
let lines = args.contains("session") ? garminSessionOracleLines()
    : args.contains("sync") ? garminSyncOracleLines() : garminOracleLines()
for line in lines { print(line) }
