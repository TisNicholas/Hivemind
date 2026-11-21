# HiveMind (Forge 1.12.2)

Build: copy `src/` and `build.gradle` into the official Forge 1.12.2 MDK (Java 8), then run `gradlew build`.
The jar lands in `build/libs/`. Drop it in `mods/` (server-side only is fine).

Config: `config/hivemind.cfg` (generated on first launch).
  mobs = minecraft:zombie, somemod:*, ...   ("ModID:*" = every mob from that mod, "*" = everything)
  excludedMobs = same syntax, always wins over `mobs`

Commands (op level 2): /hivemind strategy | mobs [name] | player [name] | live | stats | save | reset
Brain file: <world>/hivemind_brain.dat

Observation
  Player: playstyle profile (aggression, ranged, blocking, fleeing, turtling, building, digging, mobility...)
          saved to <world>/hivemind_players.dat and fed into the neural network.
  Mobs:   damage dealt/taken, causes of death, lifespan, walls broken, role they die in most (saved in the brain file).
Note: this version changes the brain's input size, so a brain file from an earlier build is discarded automatically.
