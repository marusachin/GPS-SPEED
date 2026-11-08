# GPS Speedometer & Trip Tracker

This Android app shows GPS speed in km/h, current GPS accuracy/status, your live location and route on an OpenStreetMap map, and lets you pause/resume and save trip summaries (distance and maximum speed) in local trip history.

## Build the APK on GitHub (no Android Studio required)

1. Sign in to GitHub and create a **Public** repository named `gps-speedometer`.
2. On the repository page, choose **Add file → Upload files**.
3. Upload the contents of this project folder, keeping the `.github/workflows/build-apk.yml` path. Commit the upload to the `main` branch.
4. Open the repository's **Actions** tab. Wait for **Build GPS Speedometer APK** to finish with a green check.
5. Open that completed run, scroll to **Artifacts**, and download `GPS-Speedometer-v1.3`.
6. Extract the downloaded ZIP and open `GPS-Speedometer-v1.3.apk`. If Android asks, allow your browser or Files app to install unknown apps, then tap Install.

The debug APK is signed with Android's standard debug key and is installable. It is intended for personal use and is not a Play Store release.

## Permissions and map

Grant location permission and turn on Location/GPS. A network connection is needed to load map tiles and the Leaflet map library. GPS collection runs while the app is open. The live map arrow follows the direction of travel, and current GPS altitude is shown in meters. Trip summaries and route/altitude samples are stored on the device. Open **History** and select a trip to see its route, duration, average speed, maximum speed, and altitude profile. Trips saved by older versions did not store route or altitude samples, so those older details cannot be recovered.

