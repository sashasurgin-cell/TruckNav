# TruckNav

TruckNav is an Android truck-navigation project focused on commercial drivers in the contiguous United States. It is **truck-only**: there is no passenger-car mode.

## Product direction

The product takes inspiration from the workflow of truck-driver apps such as Trucker Path: truck-friendly trip planning, vehicle profiles, and a map organized around truck-driver points of interest (POIs). TruckNav is an independent implementation and does not copy Trucker Path's proprietary code, map tiles, or private POI database.

## Planned POI categories

- Truck stops and travel centers
- Truck parking and parking availability
- Diesel and DEF fuel
- Weigh stations and CAT scales
- Rest areas
- Truck repair shops and dealerships
- Truck washes
- Low-clearance bridges and road restrictions
- Truck-friendly stores and overnight parking locations where verified
- Hotels and restaurants
- Custom locations such as yards, docks, warehouses, and customer entrances

## Current prototype

- Android app built with Kotlin and Jetpack Compose
- OpenStreetMap tile map using osmdroid; no Google Maps API key is required to display the map
- Quick-access chips for all 12 truck-driver POI categories on the map
- Initial live POI lookup using the OpenStreetMap Overpass API, with map markers and a manual “Search here” refresh
- HERE Routing API v8 integration for truck routes using the entered height, width, length, gross weight, and axle count; routes are drawn on the map
- Russian / English interface toggle
- Truck-only navigation UI with Map, Trip, POI, Saved, and Truck tabs
- Initial truck profile fields: vehicle type, height, width, length, weight, and axle count
- GitHub Actions builds a debug APK and uploads it as an artifact

## Important limitations

The current build is an early prototype, not yet a production navigation system. POI lookup now queries OpenStreetMap data through the public Overpass API for the selected category near the map center. Coverage depends on community mapping and the public service may be rate-limited or unavailable. Live parking availability, fuel prices, weigh-station status, turn-by-turn voice guidance, and persistent saved profiles are not yet implemented. Truck routing requires a HERE API key configured as the `HERE_API_KEY` GitHub Actions secret. HERE truck routes are best-effort and must not be treated as a guarantee that every restriction or road condition is correct.

A map being visible does not mean a route is certified safe or legal for a truck. TruckNav must show the source and freshness of restriction data and clearly warn when routing data is incomplete or uncertain.

## Data and services strategy

Use licensed APIs and/or legally reusable datasets for road routing and POIs. Do not scrape or copy Trucker Path's private map or proprietary POI database. Any commercial provider will require review of its coverage, license, pricing, rate limits, and permitted display/use.

The map layer uses OpenStreetMap tiles and POI markers are queried from OpenStreetMap contributors via Overpass. The HERE Routing API v8 integration uses `transportMode=truck` and the vehicle dimensions/weight fields. Both the road-restriction data and POI coverage can be incomplete; routes must not be represented as guaranteed safe or legal for every truck.

## Build

Pushes to `main` trigger the GitHub Actions workflow. Open the repository's **Actions** tab, select the latest successful run, and download the `TruckNav-debug-apk` artifact.

## HERE routing setup

1. Create a HERE platform account and an API key with access to Routing API v8 and Geocoding & Search.
2. In GitHub, open **Settings → Secrets and variables → Actions → New repository secret**.
3. Set the secret name to `HERE_API_KEY` and paste the key as its value.
4. Run the **Build TruckNav APK** workflow again from the Actions tab.

The map itself does not require this key. Without the secret, the map and POI view can work, but route calculation will show a setup message.
