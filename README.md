# Achievements NH

A client-side replacement of the achievements screen for GT New Horizons 2.9 (Minecraft 1.7.10), built on ModularUI2.

- Full-screen window, size configurable.
- Page list with icon, name and `unlocked/total (%)`.
- Search across all pages by name and description; filters: all / available / unlocked / locked.
- Each row shows icon, name (coloured by status), description and a status stripe.
- Details panel: description, status, page, "requires" and "unlocks" links that jump to the target.
- "Old screen" opens Better Achievements (or vanilla) if you need the tree view.

Config: `config/achievementsnh.cfg` (`hideSpoilers`, window and column sizes, `maxRows`).

Build: `./gradlew build`, jar in `build/libs/`. Requires ModularUI2 (shipped with GTNH).
