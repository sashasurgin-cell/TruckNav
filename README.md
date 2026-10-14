# TruckNav

TruckNav is an Android truck-navigation project for the United States and Canada. It is truck-only and does not provide a passenger-car navigation mode.

## Free/open-source principles

- OpenStreetMap map data and the osmdroid map library; no Google Maps API key.
- Truck-driver POI searches use OpenStreetMap data through Overpass.
- No paid API key or subscription is required to build or open the current prototype.
- The app is an independent project and does not copy Trucker Path's proprietary code, tiles, or private POI database.
- OpenStreetMap attribution and license requirements must be preserved.

## Current prototype (v0.6.0)

- Kotlin + Jetpack Compose Android app.
- OpenStreetMap map centered initially on Chicago.
- Quick filters for truck parking, weigh stations, fuel/DEF, truck stops, CAT scales, rest areas, repair shops, truck wash, low bridges, Walmart, hotels, and restaurants.
- POI markers from the public Overpass API.
- Automatic POI refresh when the selected category/map area changes, plus a periodic refresh every six hours while the app remains open.
- Russian/English interface toggle.
- Vehicle profile UI for cargo van, pickup + trailer, box truck, and semi truck.
- GitHub Actions automatically builds a debug APK artifact on pushes to `main`.

## Important: truck routing is not yet implemented

This version deliberately does **not** substitute an ordinary car route for a truck route. That would be unsafe because a car-routing engine may not respect truck height, width, weight, axle, hazardous-material, and road restrictions.

The next major engineering step is a genuinely open-source truck-routing backend and data pipeline. GraphHopper is one candidate, but its server must be configured and tested with the right truck restrictions and North American data. A production-quality route engine for all of the US and Canada may need significant compute/storage; free source code does not automatically mean free hosting. Until this is implemented and validated, TruckNav must not be used as the sole source for route legality or clearance.

## Automatic data refresh

- POIs are fetched from the current OpenStreetMap/Overpass data when the user changes category or moves the map to a new area.
- The current prototype repeats the POI request every six hours while open.
- This is not yet a persistent local database sync: results are not yet cached for offline use, and background refresh after the app is closed is not implemented.
- Overpass is a shared public service and may rate-limit requests. For a wider release, use a self-managed or explicitly permitted data pipeline and obey the source's usage policies.
- OSM coverage varies. Parking availability, fuel prices, weigh-station open/closed status, and community reports are not currently live features.

## Build an APK

1. Open this repository's **Actions** tab on GitHub.
2. Select the latest **Build TruckNav APK** workflow run.
3. After a successful run, download the `TruckNav-debug-apk` artifact.
4. Extract the ZIP to obtain `app-debug.apk`, then transfer it to your Android device and install it.

The APK artifact is retained for 14 days.

## Open data and licensing

OpenStreetMap data is available under the Open Database License (ODbL); attribution is required. osmdroid and other dependencies retain their own licenses. Review licenses and tile-service policies before public distribution.

## Roadmap

1. Verify Android build and map rendering.
2. Add persistent POI cache and safe refresh/retry behavior.
3. Add free/open geocoding for US and Canadian addresses.
4. Integrate and test a self-hosted open-source truck-routing engine with configurable dimensions/weight/axles.
5. Add multi-stop routes, spoken turn instructions, route warnings, and user-contributed reports.
