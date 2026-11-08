# GPS Speedometer & Trip Tracker

GPS speed in km/h, GPS status and accuracy, live location and route, trip recording/history, satellite details, trip route/altitude profiles, and a switchable 3D terrain/building map.

## Build and install

1. In GitHub, open **Actions → Build GPS Speedometer APK** and wait for the latest run to show a green check.
2. Open that run and download artifact **GPS-Speedometer-v2.2**.
3. Extract the downloaded ZIP, tap `GPS-Speedometer-v2.2.apk`, allow installation when Android prompts, and tap **Install**.

This is a personally installable debug APK, not a Play Store release.

## Map and elevation

The familiar OpenStreetMap map appearance is restored. Tap **3D** to tilt the map and show raised terrain and 3D buildings where height data is available; tap **2D** to return to the flat map. The map uses OpenStreetMap tiles, OpenFreeMap building data, and Mapterhorn terrain tiles. An internet connection is required; attribution is shown on the map.

The map follows GPS initially and automatically resumes following when you start or resume trip recording. Drag the map to explore elsewhere; GPS updates pause recentering. Tap **◎** to return to your current location and resume following GPS.

Tap **⛶** to expand the map. Altitude appears at upper left, direction in degrees at upper right, and current/max speed at lower left. Tap the altitude unit button to switch between meters and feet. Tap any map location to request its terrain elevation; the coordinates of that point are sent to Open-Meteo’s Elevation API. The returned terrain estimate is based on the Copernicus DEM at approximately 90 m resolution. The popup credits Open-Meteo and Copernicus DEM.

## Trip playback

Open **History**, select a trip, then use **Play**, the speed selector, and the seek bar on the trip map. A direction arrow follows the saved route while the map pans along with it, and the recorded speed and elapsed time are shown. The single graph plots altitude vertically against distance travelled horizontally. Its dot moves with replay; drag the dot to seek manually, and the speed label above it updates. Newly recorded trips save per-point GPS speed and bearing. Older trips estimate speed from route points and timestamps where possible.

GPS speed and route recording require location permission and Location/GPS enabled. The live map arrow keeps its last reliable direction when movement is too slow for a stable heading. Android 14 and later can show mean-sea-level altitude when geoid data is available; older versions show GPS altitude above the WGS84 ellipsoid.




