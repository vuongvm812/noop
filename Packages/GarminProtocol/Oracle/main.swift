// Standalone parity-oracle entry point; see Tests/GarminProtocolTests/GarminOracleCases.swift for the build line.
// With the argument `session` it prints the session oracle instead (GarminSessionOracleCases.swift).
let lines = CommandLine.arguments.contains("session") ? garminSessionOracleLines() : garminOracleLines()
for line in lines { print(line) }
