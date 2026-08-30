![Android CI](https://github.com/eklavyamankar21-cloud/OfflineNav/actions/workflows/android-build.yml/badge.svg)

# OfflineNav

OfflineNav is a hybrid Android navigation engine built to handle both remote off-the-grid routing and live city traffic. The goal was to create a system that doesn't drain the battery or drop navigation when cell service is lost, allowing it to smoothly switch between offline map data and online live traffic updates.

### The Tech Stack
- Kotlin and Jetpack Compose for the Android user interface
- GitHub Actions for the automated CI/CD pipeline and APK generation
- Java Spring Boot (planned for future cloud syncing)

### Maps and APIs Used
- MapLibre Native for smooth, hardware-accelerated vector tile rendering
- GraphHopper 3D engine for calculating offline routes and generating turn-by-turn maneuvers
- TomTom Routing API for fetching live traffic data and calculating alternate online routes
- OpenStreetMap (OSM) data for the foundational offline map files

### How It Renders and Functions
Instead of forcing a massive global map installation, the engine allows users to dynamically download specific regional maps directly to their device storage. Whether you need just Telangana, Maharashtra, or the entire map of India, you can pull down exactly what you need for completely offline routing out in the field.

While driving, the engine relies on a mathematical road-snapping algorithm to lock your GPS coordinates precisely to the rendered roads. To prevent battery drain and overheating, the app monitors your speed. When you stop at a red light or get stuck in parked traffic, it throttles the internal GPS processing loop from 1-second intervals down to 3-second intervals. 

For trips into hilly terrain, the app reads offline topographical data and dynamically draws an elevation profile graph on the routing screen, tagging the peak altitude of your journey. It also manages audio focus at the operating system level, automatically lowering your music volume when the turn-by-turn voice guidance needs to speak.

### How to Download and Install
You don't need Android Studio or any coding knowledge to install the app. 

1. Go to the Actions tab at the top of this GitHub repository.
2. Click on the most recent green workflow run.
3. Scroll to the very bottom of the page to the Artifacts section.
4. Click on app-debug to download the packaged zip file.
5. Extract the zip, transfer the APK to your Android device, and install it.
6. Open the app and use the download manager to select and fetch your desired region (e.g., Telangana, Maharashtra, or India) directly to your phone's offline storage.
