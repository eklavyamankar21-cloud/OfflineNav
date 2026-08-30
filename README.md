![Android CI](https://github.com/eklavyamankar21-cloud/OfflineNav/actions/workflows/android-build.yml/badge.svg)

# OfflineNav

OfflineNav is a hybrid Android navigation engine built to handle both remote off-the-grid routing and live city traffic. The engine transitions smoothly between offline map data and online live traffic updates, ensuring you never lose guidance even when cell service drops.

### Architecture and Tech Stack
- Kotlin and Jetpack Compose for the Android UI and bottom sheet layouts
- GitHub Actions for the automated CI/CD pipeline and continuous APK generation
- Java Spring Boot planned for future backend cloud syncing
- Custom mathematical road-snapping algorithm that locks GPS coordinates precisely to rendered roads using orthogonal projection
- Dynamic GPS polling that throttles down to 3-second intervals when stationary to prevent battery drain, and ramps up to 1-second intervals while driving
- OS-level audio focus management that automatically ducks background music volume during turn-by-turn voice prompts

### Maps, Datasets, and APIs
- OpenStreetMap (OSM) raw data provides the foundational mapping
- Geofabrik regional extracts supply the highly compressed .osm.pbf map datasets used for routing
- Pre-generated MapLibre .mbtiles provide offline hardware-accelerated vector tile rendering without needing internet access
- Digital Elevation Model (DEM) data enables dynamic 3D topological graphs and peak altitude tags on the routing screen
- GraphHopper 3D routing engine calculates offline paths, parses the elevation data, and generates turn-by-turn maneuvers
- TomTom Routing API fetches live traffic congestion data and calculates alternate online routes
- MapLibre Native renders the vector tiles smoothly directly on the device

### Dynamic Map Downloader
Instead of forcing a massive global map installation, the engine features an in-app download manager. Users can pull down exactly what they need for off-the-grid routing directly to their device storage. Whether it is a smaller region like Telangana or Maharashtra, or the entire 1.6 GB dataset for India, the map data is fetched on demand.

### How to Download and Install
You do not need Android Studio or coding knowledge to install this engine. 

1. Go to the Actions tab at the top of this GitHub repository.
2. Click on the most recent green workflow run.
3. Scroll to the bottom of the page to the Artifacts section.
4. Click on app-debug to download the packaged zip file.
5. Extract the zip, transfer the APK to your Android phone, and install it.
6. Open the app and use the download manager to select your desired region and fetch the offline .pbf and .mbtiles datasets directly to your local storage.
