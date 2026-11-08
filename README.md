# GPS Speedometer & Trip Tracker

GPS speed in km/h, GPS status and accuracy, live location and route, trip recording/history, satellite details, trip route/altitude profiles, and a switchable 3D map view.

## Build and install

1. In GitHub, open **Actions → Build GPS Speedometer APK** and wait for the latest run to show a green check.
2. Open that run and download artifact **GPS-Speedometer-v1.8**.
3. Extract the downloaded ZIP, tap `GPS-Speedometer-v1.8.apk`, allow installation when Android prompts, and tap **Install**.

This is a personally installable debug APK, not a Play Store release.

## Map and elevation

Tap **3D** to tilt the map and show 3D building shapes where building-height data is available. Tap **2D** to return to the flat map. The 3D map uses MapLibre GL JS with OpenFreeMap vector tiles, which require internet access. This view extrudes buildings; it does not add 3D terrain relief.

Tap **⛶** to expand the map. Altitude appears at upper left, direction in degrees at upper right, and current/max speed at lower left. Tap the altitude unit button to switch between meters and feet. Tap any map location to request its terrain elevation; the coordinates of that tapped point are sent to Open-Meteo’s Elevation API. Terrain height comes from the Copernicus DEM at approximately 90 m resolution, so it is an estimate rather than a survey measurement. The popup credits Open-Meteo and Copernicus DEM.

GPS speed and route recording require location permission and Location/GPS enabled. The live map arrow keeps the last reliable direction when movement is too slow for a stable heading. Android 14 and later can show mean-sea-level altitude when geoid data is available; older versions show GPS altitude above the WGS84 ellipsoid.

