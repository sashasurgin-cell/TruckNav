# Low-clearance data for TruckNav

## Sources and intended use

TruckNav currently queries OpenStreetMap (OSM) around the visible map area for road segments tagged with `maxheight`, `maxheight:physical`, `maxheight:conditional`, and height-restrictor barriers. The app displays the recorded clearance when available and explicitly marks restrictions whose height is unknown.

- OSM `maxheight` documentation: https://wiki.openstreetmap.org/wiki/Key:maxheight
- FHWA National Bridge Inventory (NBI): https://www.fhwa.dot.gov/bridge/nbi/ascii.cfm
- FHWA's 2025 NBI download: https://www.fhwa.dot.gov/bridge/nbi/ascii2025.cfm

## Important limitation

The NBI is an inventory of bridge structures, not a complete current database of road clearances. FHWA explicitly warns that NBI data should not be used for route-clearance decisions because clearance values may be stale. NBI records should not be treated as safe-to-pass height data.

OSM coverage is crowd-sourced and incomplete. A missing OSM restriction does **not** mean a bridge is high enough for the vehicle. A recorded height may also be outdated or apply only to a particular lane/direction.

## Current implementation status

- [x] Query OSM low-clearance restrictions near the map center.
- [x] Include legal, physical and conditional max-height tags, plus height-restrictor barriers.
- [x] Display a dedicated warning marker and the recorded height when present.
- [x] Show an explicit warning when the height is unknown.
- [ ] Build and host a versioned nationwide offline snapshot with source timestamp and OSM attribution.
- [ ] Merge and deduplicate state DOT clearance datasets where redistribution terms allow.
- [x] Check the calculated route against the current nationwide OSM snapshot and list nearby known low-clearance restrictions.
- [ ] Add audible alerts for upcoming restrictions during active navigation.
- [ ] Merge and validate official state DOT datasets; current OSM coverage is not comprehensive.

Do not advertise the current app data as a complete national database and do not use it as the sole authority for clearance decisions. Before driving, follow posted signs and official restrictions.
