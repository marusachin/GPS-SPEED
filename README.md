# GPS Speedometer & Trip Tracker

GPS speed in km/h, GPS status and accuracy, live location and route, trip recording/history, satellite details, trip route/altitude profiles, and a switchable 3D terrain and building map.

## Build and install

1. In GitHub, open **Actions → Build GPS Speedometer APK** and wait for the latest run to show a green check.
2. Open that run and download artifact **GPS-Speedometer-v1.9**.
3. Extract the downloaded ZIP, tap `GPS-Speedometer-v1.9.apk`, allow installation when Android prompts, and tap **Install**.

This is a personally installable debug APK, not a Play Store release.

## Map, terrain, and elevation

Tap **3D** to tilt the map and show raised terrain and building shapes where height data is available. Tap **2D** to return to the flat map. OpenFreeMap vector tiles provide the map/buildings; Mapterhorn supplies 3D terrain elevation tiles. An internet connection is needed. Terrain tile attribution is **Terrain © Mapterhorn**; map/building data attribution is shown on the map.

The map follows GPS initially. Drag the map to explore elsewhere; GPS updates no longer recenter it. Tap the **◎** button to return to your current location and resume following GPS.

Tap **⛶** to expand the map. Altitude appears at upper left, direction in degrees at upper right, and current/max speed at lower left. Tap the altitude unit button to switch between meters and feet. Tap any map location to request its terrain elevation; the coordinates of that tapped point are sent to Open-Meteo’s Elevation API. That value comes from the Copernicus DEM at approximately 90 m resolution, so it is an estimate rather than a survey measurement. The popup credits Open-Meteo and Copernicus DEM.

GPS speed and route recording require location permission and Location/GPS enabled. The live map arrow keeps the last reliable direction when movement is too slow for a stable heading. Android 14 and later can show mean-sea-level altitude when geoid data is available; older versions show GPS altitude above the WGS84 ellipsoid.

