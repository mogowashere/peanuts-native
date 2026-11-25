# The linked cheats folder

Settings › Library › **Linked cheats folder** names a folder on shared storage in Eden's `load` layout that another app writes cheats into (Eden Cheats
Manager, through Android's folder access). Peanuts Native reads it with All files access and keeps each game's cheat sources in step with it. Player
words: [FAQ.md](FAQ.md#can-eden-cheats-manager-install-cheats-for-peanuts-native).

The code: `dev.peanuts.mods.CheatFolderSync` (the sync, no Android classes; JVM test `CheatFolderSyncJvmTest`), `dev.peanuts.mods.CheatLink` (the Android side:
the setting, the sync's log line, the library file), the hook in `CheatsBridge.wanted` (a start and the Mods & Cheats page both pass there), the row in
`dev.peanuts.ui.LibrarySettings`, and `Library.writeCheatLink` after each scan. Tests: `peanuts/tests/test_cheat_link.py`.

## The folder (written by the other app)

`<load>/<title ID>/<set name>/cheats/<build ID>.txt`, the Atmosphère cheat format, as Eden and Eden Cheats Manager write it. The build ID is the first 16 hex
digits of main's build ID (a 32-digit name is taken by its first 16). The set name becomes the source's name.

- Each set folder of the game's title IDs (the base and its update and DLC IDs, `CheatImport.sameGame`) is one source, `from=link`, its folder name kept as
  `link` in its `source.tsv`. A new set becomes a source; a set whose files changed gets the new files, its on / off and its cheats' switches kept; a source whose
  set is gone is removed. Sources imported by hand are never touched.
- A folder that cannot be read changes nothing. A set whose every file is refused this time (one being written) keeps its source as it is.
- Files over 256 KB, files that are not text and files with no cheat entry are skipped, as an import skips them. At most 64 sets per game.

## `<load>/peanuts-library.json` (written by Peanuts Native)

```json
{
  "format": 1,
  "app_version": "0.1.8",
  "package": "dev.nxnative.android.peanuts",
  "written": 1764050000000,
  "games": [
    { "title_id": "0100000000010000", "name": "Super Mario Odyssey", "ready": true,
      "builds": [ { "build_id": "3CA12DFAAF9C82DA", "version": "1.4.1" } ] }
  ]
}
```

- `games`: every game of the library that has a profile. `ready` is true when the game is built and playable. `builds` are its main builds
  (`CheatsBridge.builds`), each with the version `game.tsv` names for it (`CheatsBridge.version`) when it names one. The build ID above is a placeholder.
- Written after each library scan and when the folder is set, through a temporary file and a rename. A reader ignores fields it does not know and refuses a
  higher `format`.
- Beside it, an empty `<title ID>/` folder per game: a reader that lists a `load` folder's title ID folders (Eden Cheats Manager does) sees the games without
  reading the file.
