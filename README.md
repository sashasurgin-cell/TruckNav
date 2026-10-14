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
- Google Maps Android SDK map view
- Russian / English interface toggle
- Truck-only navigation UI with Map, Trip, POI, Saved, and Truck tabs
- Initial truck profile fields: vehicle type, height, width, length, weight, and axle count
- GitHub Actions builds a debug APK and uploads it as an artifact

## Important limitations

The current build is a UI and map foundation, not yet a production navigation system. The route calculation engine, live POI provider, live parking status, fuel prices, weigh-station status, turn-by-turn voice guidance, and persistent saved profiles still need to be integrated and tested.

A map being visible does not mean a route is certified safe or legal for a truck. TruckNav must show the source and freshness of restriction data and clearly warn when routing data is incomplete or uncertain.

## Data and services strategy

Use licensed APIs and/or legally reusable datasets for road routing and POIs. Do not scrape or copy Trucker Path's private map or proprietary POI database. Any commercial provider will require review of its coverage, license, pricing, rate limits, and permitted display/use.

Google Maps SDK currently provides the map layer in the prototype. Truck-specific routing and POI coverage are separate integrations and must be evaluated independently.

## Build

Pushes to `main` trigger the GitHub Actions workflow. Open the repository's **Actions** tab, select the latest successful run, and download the `TruckNav-debug-apk` artifact.
