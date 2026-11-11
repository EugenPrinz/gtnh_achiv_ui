# Achievements NH

A client-side replacement of the achievements screen for GT New Horizons 2.9 (Minecraft 1.7.10), built on ModularUI2.

- Full-screen window, size configurable.
- Page list with icon, name and `unlocked/total (%)`.
- Search across all pages by name and description; filters: all / available / unlocked / locked.
- Each row shows icon, name (coloured by status), description and a status stripe.
- Details panel: description, status, page, "requires" and "unlocks" links that jump to the target.
- "Old screen" opens Better Achievements (or vanilla) if you need the tree view.

Config: mod list → Achievements NH → Config, or `config/achievementsnh.cfg` (`hideSpoilers`, window and column sizes, `maxRows`, `showFps`, `backgroundOpacity`). Pins: `config/achievementsnh-pinned.txt`.

Build: `./gradlew build`, jar in `build/libs/`. Requires ModularUI2 (shipped with GTNH).

## Hotkeys
(only while the search field is not focused)

| Key | Action |
| --- | --- |
| Ctrl+F | Focus search |
| Tab | Toggle list / tree |
| Up / Down | Previous / next page |
| Enter | Show the selected achievement |
| N | Next achievement you can unlock now |
| P | Pin / unpin the selected achievement |
| R / U (hover) | NEI recipe / usage of the achievement's item |
