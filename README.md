# GPS Speedometer & Trip Tracker

GPS speed in km/h, GPS status and accuracy, live location and route, trip recording/history, satellite details, and trip route/altitude profiles.

## Build and install

1. In GitHub, open **Actions → Build GPS Speedometer APK** and wait for the latest run to show a green check.
2. Open that run and download artifact **GPS-Speedometer-v1.6**.
3. Extract the downloaded ZIP, tap `GPS-Speedometer-v1.6.apk`, allow installation when Android prompts, and tap **Install**.

This is a personally installable debug APK, not a Play Store release.

## Full-screen map and elevation

Tap **⛶** on the map to expand it. Altitude appears at upper left, direction in degrees at upper right, and current/max speed at lower left. Tap the altitude unit button to switch between meters and feet. Tap a map location to request its terrain elevation; the coordinates of that tapped point are sent to Open-Meteo’s Elevation API. Terrain height comes from the Copernicus DEM at approximately 90 m resolution, so it is an estimate rather than a survey measurement. The map popup credits Open-Meteo and Copernicus DEM.

The map tiles and Leaflet library require internet access. GPS speed and route recording require location permission and Location/GPS enabled. The live map arrow uses the last reliable direction when movement is too slow for a stable heading. Android 14 and later can show mean-sea-level altitude when geoid data is available; older versions show GPS altitude above the WGS84 ellipsoid.

