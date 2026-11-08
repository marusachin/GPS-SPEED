package com.example.gpsspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity implements LocationListener {
    private static final int REQUEST_LOCATION = 10;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationClient;
    private boolean highAccuracyLocation = true;
    private float latestAccuracyMeters = Float.NaN;
    private Location lastAcceptedMapLocation;
    private float smoothedSpeedKmh = 0;
    private long lastAcceptedMovementAt = 0;
    private final LocationCallback fusedLocationCallback = new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) {
            if (result != null && result.getLastLocation() != null) MainActivity.this.onLocationChanged(result.getLastLocation());
        }
    };
    private TextView speedView, detailView, tripView, signalView, altitudeView, locationModeView;
    private WebView map;
    private View dashboard;
    private LinearLayout bottomControls;
    private Button recordButton;
    private boolean fullScreenMap = false;
    private boolean mapReady = false, recording = false, paused = false;
    private final ArrayList<Location> points = new ArrayList<>();
    private float maxKmh = 0;
    private long startedAt = 0;
    private double distanceMeters = 0;
    private long activeMillis = 0, activeSegmentStartedAt = 0;
    private Location previousTripPoint = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService altitudeWorker = Executors.newSingleThreadExecutor();
    private Location latestAltitudeLocation;
    private boolean altitudeWarmupRunning = false, altitudeMslReady = false;
    private double latestSpeedKmh=0, sessionMaxKmh=0, latestAltitudeMeters=0;
    private boolean latestAltitudeKnown=false, latestAltitudeMsl=false;
    private double latestDirectionDegrees=Double.NaN;
    private final ExecutorService elevationLookupExecutor=Executors.newSingleThreadExecutor();
    private final AtomicInteger elevationQueryId=new AtomicInteger(0);
    private int satellitesInFix = 0;
    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0; float strongest = 0;
            for (int i=0; i<status.getSatelliteCount(); i++) if(status.usedInFix(i)) { used++; strongest=Math.max(strongest,status.getCn0DbHz(i)); }
            satellitesInFix=used; updateSignal(strongest); updateMapGpsInfo();
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(10, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(10, 18, 32));
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        highAccuracyLocation = getSharedPreferences("gps_settings", MODE_PRIVATE).getBoolean("high_accuracy_location", true);
        migrateOldHistory();
        buildUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        else startGps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(10,18,32));
        dashboard=new LinearLayout(this);((LinearLayout)dashboard).setOrientation(LinearLayout.VERTICAL);root.addView(dashboard,new LinearLayout.LayoutParams(-1,-2));
        root.setOnApplyWindowInsetsListener((v,insets)->{int top=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(dp(12),top+dp(4),dp(12),bottom+dp(8));return insets;});
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(4,dp(6),4,dp(4));
        TextView title = new TextView(this); title.setText("GPS SPEEDOMETER"); title.setTextColor(Color.WHITE); title.setTextSize(16); title.setGravity(Gravity.CENTER_VERTICAL); title.setTypeface(null,android.graphics.Typeface.BOLD); header.addView(title,new LinearLayout.LayoutParams(0,dp(38),1));
        signalView = new TextView(this); signalView.setText("● GPS searching"); signalView.setTextColor(Color.rgb(255,190,80)); signalView.setTextSize(13); signalView.setGravity(Gravity.CENTER_VERTICAL); header.addView(signalView); dashboardAdd(header);
        speedView = new TextView(this); speedView.setText("0"); speedView.setTextColor(Color.WHITE); speedView.setTextSize(62); speedView.setGravity(Gravity.CENTER); dashboardAdd(speedView,new LinearLayout.LayoutParams(-1,dp(84)));
        TextView units = new TextView(this); units.setText("km/h"); units.setTextColor(Color.rgb(90,190,255)); units.setTextSize(16); units.setGravity(Gravity.CENTER); dashboardAdd(units,new LinearLayout.LayoutParams(-1,dp(25)));
        altitudeView = new TextView(this); altitudeView.setText("GPS altitude — m"); altitudeView.setTextColor(Color.WHITE); altitudeView.setTextSize(12); altitudeView.setGravity(Gravity.CENTER); altitudeView.setPadding(0,dp(2),0,dp(2)); dashboardAdd(altitudeView,new LinearLayout.LayoutParams(-1,dp(24)));
        detailView = new TextView(this); detailView.setText("Waiting for location • Accuracy —"); detailView.setTextColor(Color.LTGRAY); detailView.setTextSize(13); detailView.setGravity(Gravity.CENTER); detailView.setPadding(8,dp(2),8,dp(2)); dashboardAdd(detailView);
        locationModeView = new TextView(this); locationModeView.setTextColor(Color.rgb(90,190,255)); locationModeView.setTextSize(11); locationModeView.setGravity(Gravity.CENTER); locationModeView.setPadding(0,0,0,dp(5)); locationModeView.setOnClickListener(v -> showLocationModeDialog()); updateLocationModeLabel(); dashboardAdd(locationModeView,new LinearLayout.LayoutParams(-1,dp(22)));
        map = new WebView(this); map.setBackgroundColor(Color.rgb(30,42,55)); map.getSettings().setJavaScriptEnabled(true); map.getSettings().setUserAgentString(map.getSettings().getUserAgentString()+" GPS-Speedometer/2.0"); map.setWebChromeClient(new WebChromeClient()); map.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String u){mapReady=true;}}); map.addJavascriptInterface(this,"Android"); map.loadUrl("file:///android_asset/map.html");
        root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        bottomControls = new LinearLayout(this); bottomControls.setOrientation(LinearLayout.VERTICAL);root.addView(bottomControls,new LinearLayout.LayoutParams(-1,-2));
        tripView = new TextView(this); tripView.setText("Trip: 0.00 km   •   Max: 0 km/h"); tripView.setTextColor(Color.WHITE); tripView.setTextSize(15); tripView.setGravity(Gravity.CENTER); tripView.setPadding(4,dp(8),4,dp(8)); bottomControls.addView(tripView,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout buttons = new LinearLayout(this); buttons.setPadding(0,dp(2),0,dp(6)); buttons.setGravity(Gravity.CENTER); buttons.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = button("START"); recordButton.setOnClickListener(v -> toggleTrip()); buttons.addView(recordButton,new LinearLayout.LayoutParams(0,dp(48),1));
        Button stop = button("STOP"); stop.setOnClickListener(v -> confirmStop()); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,dp(48),1); sp.leftMargin=dp(6); buttons.addView(stop,sp);
        Button history = button("HISTORY"); history.setOnClickListener(v -> showHistory()); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0,dp(48),1); hp.leftMargin=dp(6); buttons.addView(history,hp);
        Button satellites = button("SATELLITES"); satellites.setTextSize(11); satellites.setOnClickListener(v -> startActivity(new Intent(this,SatelliteActivity.class))); LinearLayout.LayoutParams satp = new LinearLayout.LayoutParams(0,dp(48),1); satp.leftMargin=dp(6); buttons.addView(satellites,satp);
        bottomControls.addView(buttons);
        setContentView(root);
    }
    private void dashboardAdd(View view){((LinearLayout)dashboard).addView(view);}
    private void dashboardAdd(View view,LinearLayout.LayoutParams params){((LinearLayout)dashboard).addView(view,params);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextSize(13); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(4),0,dp(4),0); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24,116,210))); return b; }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){ super.onRequestPermissionsResult(requestCode,permissions,results); if(requestCode==REQUEST_LOCATION && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) startGps(); else detailView.setText("Location permission is needed for GPS speed and route."); }
    private void startGps(){ try { if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return; stopLocationUpdates(); if(highAccuracyLocation){ LocationRequest request=new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,1000L).setMinUpdateIntervalMillis(750L).setWaitForAccurateLocation(true).build(); fusedLocationClient.requestLocationUpdates(request,fusedLocationCallback,Looper.getMainLooper()).addOnFailureListener(error->{ try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); detailView.setText("High accuracy unavailable • using GPS only"); } catch(Exception ignored){detailView.setText("Turn on Location in phone settings.");} }); detailView.setText("High accuracy on • Waiting for location…"); }else{ locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); detailView.setText("GPS on • Waiting for a satellite fix…"); } if(Build.VERSION.SDK_INT>=24){try{locationManager.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){} locationManager.registerGnssStatusCallback(gnssCallback,new Handler(Looper.getMainLooper()));} updateLocationModeLabel(); } catch(Exception e){detailView.setText("Turn on Location in phone settings.");} }
    private void stopLocationUpdates(){ try{locationManager.removeUpdates(this);}catch(Exception ignored){} if(fusedLocationClient!=null)fusedLocationClient.removeLocationUpdates(fusedLocationCallback); }
    private void showLocationModeDialog(){ String[] modes={"High accuracy (GPS + Wi‑Fi/cellular)","GPS only (satellites)"}; int checked=highAccuracyLocation?0:1; new AlertDialog.Builder(this).setTitle("Location mode").setMessage("High accuracy asks Android to use available GPS, Wi-Fi, cellular networks and sensors. It may use more battery; available sources depend on your phone settings.").setSingleChoiceItems(modes,checked,(dialog,which)->{highAccuracyLocation=(which==0);getSharedPreferences("gps_settings",MODE_PRIVATE).edit().putBoolean("high_accuracy_location",highAccuracyLocation).apply();updateLocationModeLabel();startGps();dialog.dismiss();}).setNegativeButton("Cancel",null).show(); }
    private void updateLocationModeLabel(){ if(locationModeView!=null)locationModeView.setText(highAccuracyLocation?"Location: High accuracy • Tap to change":"Location: GPS only • Tap to change"); }
    private void updateMapGpsInfo(){ if(mapReady && !Float.isNaN(latestAccuracyMeters)){map.evaluateJavascript("javascript:setGpsInfo("+latestAccuracyMeters+","+satellitesInFix+","+highAccuracyLocation+")",null);} }
    private void updateSignal(float cn0){ if(signalView==null)return; String label=satellitesInFix==0?"● GPS searching":satellitesInFix+" satellites • "+(cn0>=30?"Strong":cn0>=20?"Fair":"Weak"); signalView.setText(label);signalView.setTextColor(satellitesInFix==0?Color.rgb(255,190,80):cn0>=20?Color.rgb(90,220,140):Color.rgb(255,190,80)); }
    @Override public void onLocationChanged(Location loc){
        if(loc==null)return;
        long now=System.currentTimeMillis();
        float accuracy=loc.hasAccuracy()?loc.getAccuracy():Float.POSITIVE_INFINITY;
        latestAccuracyMeters=accuracy;
        boolean gps=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        String fixLabel=accuracy>35f?"Weak GPS fix":(highAccuracyLocation?"High accuracy fix":(gps?"GPS fix":"GPS off"));
        String accuracyLabel=(!Float.isNaN(accuracy)&&!Float.isInfinite(accuracy))?String.format(Locale.getDefault()," • Accuracy ±%.0f m",accuracy):" • Accuracy unavailable";
        detailView.setText(fixLabel+accuracyLabel);
        updateMapGpsInfo();

        if(Float.isNaN(accuracy)||Float.isInfinite(accuracy)||accuracy>35f){
            if(now-lastAcceptedMovementAt>2500)smoothedSpeedKmh=0;
            latestSpeedKmh=smoothedSpeedKmh;
            speedView.setText(String.format(Locale.getDefault(),"%.0f",latestSpeedKmh));
            updateTrip();
            updateMapInfo();
            return;
        }

        Location previous=lastAcceptedMapLocation;
        float movedMeters=previous==null?0:previous.distanceTo(loc);
        double elapsedSeconds=previous==null?0:(loc.getTime()-previous.getTime())/1000.0;
        if(previous!=null&&elapsedSeconds<=0)elapsedSeconds=Math.max(0.5,(now-previous.getTime())/1000.0);
        float noiseFloor=previous==null?0:Math.max(8f,Math.min(20f,Math.max(loc.getAccuracy(),previous.getAccuracy())));
        float reportedSpeed=loc.hasSpeed()?Math.max(0,loc.getSpeed()):0;
        boolean speedAccuracyGood=true;
        if(Build.VERSION.SDK_INT>=26&&loc.hasSpeedAccuracy())speedAccuracyGood=loc.getSpeedAccuracyMetersPerSecond()<=2.5f;
        float derivedSpeed=elapsedSeconds>0.25? (float)(movedMeters/elapsedSeconds):0;
        boolean speedCorroborates=loc.hasSpeed()&&speedAccuracyGood&&reportedSpeed>=0.8f&&Math.abs(derivedSpeed-reportedSpeed)<=Math.max(3f,reportedSpeed*0.65f);
        boolean positionAccepted=previous==null;
        if(previous!=null&&elapsedSeconds>0){
            float jumpLimit=(float)Math.max(50.0,elapsedSeconds*55.0);
            boolean plausibleJump=movedMeters<=jumpLimit;
            boolean enoughMovement=movedMeters>=noiseFloor;
            boolean independentlyClearMovement=movedMeters>=noiseFloor*3.0f;
            positionAccepted=plausibleJump&&enoughMovement&&(speedCorroborates||independentlyClearMovement);
            if(derivedSpeed>45f&&!speedCorroborates)positionAccepted=false;
        }

        float speedMps=reportedSpeed;
        if(!loc.hasSpeed()||!speedAccuracyGood){
            speedMps=positionAccepted&&previous!=null?derivedSpeed:0;
        }
        if(positionAccepted){
            lastAcceptedMapLocation=new Location(loc);
            if(speedMps>=0.55f){
                float sampleKmh=speedMps*3.6f;
                smoothedSpeedKmh=smoothedSpeedKmh==0?sampleKmh:(smoothedSpeedKmh*0.65f+sampleKmh*0.35f);
                lastAcceptedMovementAt=now;
            }else{
                smoothedSpeedKmh=0;
                lastAcceptedMovementAt=0;
            }
        }else if(now-lastAcceptedMovementAt>2500){
            smoothedSpeedKmh=0;
        }
        latestSpeedKmh=smoothedSpeedKmh;
        if(positionAccepted)sessionMaxKmh=Math.max(sessionMaxKmh,latestSpeedKmh);
        speedView.setText(String.format(Locale.getDefault(),"%.0f",latestSpeedKmh));

        if(positionAccepted)prepareAltitude(loc);
        if(recording&&!paused&&positionAccepted&&accuracy<=35f){
            Location trackPoint=new Location(loc);
            trackPoint.setSpeed((float)(latestSpeedKmh/3.6));
            if(previousTripPoint==null){
                previousTripPoint=trackPoint;
                if(points.size()<6000)points.add(new Location(trackPoint));
            }else{
                double trackElapsed=Math.max(0.5,(trackPoint.getTime()-previousTripPoint.getTime())/1000.0);
                float trackDistance=previousTripPoint.distanceTo(trackPoint);
                if(trackDistance<=Math.max(50.0,trackElapsed*55.0)){
                    distanceMeters+=trackDistance;
                    previousTripPoint=trackPoint;
                    if(points.size()<6000)points.add(new Location(trackPoint));
                    maxKmh=Math.max(maxKmh,(float)latestSpeedKmh);
                }
            }
        }
        updateTrip();
        if(mapReady&&positionAccepted){
            double bearing=loc.hasBearing()?loc.getBearing():-1;
            double bearingAccuracy=Build.VERSION.SDK_INT>=26&&loc.hasBearingAccuracy()?loc.getBearingAccuracyDegrees():-1;
            double speed=latestSpeedKmh/3.6;
            String js="javascript:setPoint("+loc.getLatitude()+","+loc.getLongitude()+","+loc.getAccuracy()+","+bearing+","+speed+","+bearingAccuracy+")";
            map.evaluateJavascript(js,null);
        }
        if(mapReady)updateMapInfo();
    }
    private void prepareAltitude(Location loc){
        if(!loc.hasAltitude()){altitudeView.setText("Altitude unavailable");latestAltitudeKnown=false;updateMapInfo();return;}
        if(Build.VERSION.SDK_INT>=34){
            if(loc.hasMslAltitude()){altitudeMslReady=true;latestAltitudeLocation=new Location(loc);showAltitude(loc,true);return;}
            if(altitudeMslReady){try{if(Api34Altitude.tryConvert(loc)){latestAltitudeLocation=new Location(loc);showAltitude(loc,true);return;}}catch(Exception ignored){}}
            latestAltitudeLocation=new Location(loc);
            if(!altitudeWarmupRunning){altitudeWarmupRunning=true;Location sample=new Location(loc);altitudeWorker.execute(()->{boolean ready=Api34Altitude.prepare(getApplicationContext(),sample);mainHandler.post(()->{altitudeWarmupRunning=false;altitudeMslReady=ready;if(ready){if(latestAltitudeLocation!=null){Location latest=new Location(latestAltitudeLocation);if(Api34Altitude.tryConvert(latest)){latestAltitudeLocation=latest;showAltitude(latest,true);}}convertRecordedPointsToMsl();}else showAltitude(latestAltitudeLocation,false);});});}
            altitudeView.setText(altitudeWarmupRunning?"Calibrating sea-level altitude…":"Sea-level altitude unavailable");latestAltitudeKnown=false;updateMapInfo();
        }else{latestAltitudeLocation=new Location(loc);showAltitude(loc,false);}
    }
    private void showAltitude(Location loc,boolean msl){
        if(loc==null||!loc.hasAltitude()){altitudeView.setText("Altitude unavailable");return;}
        double meters=msl&&Build.VERSION.SDK_INT>=34&&loc.hasMslAltitude()?loc.getMslAltitudeMeters():loc.getAltitude();
        String accuracy="";if(msl&&Build.VERSION.SDK_INT>=34&&loc.hasMslAltitudeAccuracy())accuracy=String.format(Locale.getDefault()," ±%.0f m",loc.getMslAltitudeAccuracyMeters());else if(Build.VERSION.SDK_INT>=26&&loc.hasVerticalAccuracy())accuracy=String.format(Locale.getDefault()," ±%.0f m",loc.getVerticalAccuracyMeters());
        latestAltitudeMeters=meters;latestAltitudeKnown=true;latestAltitudeMsl=msl;
        altitudeView.setText(String.format(Locale.getDefault(),msl?"Altitude %.0f m MSL%s":"GPS altitude %.0f m WGS84%s",meters,accuracy));updateMapInfo();
    }
    private void updateMapInfo(){if(mapReady)map.evaluateJavascript("javascript:setMapInfo("+latestSpeedKmh+","+(recording?maxKmh:sessionMaxKmh)+","+latestAltitudeMeters+","+latestAltitudeKnown+","+latestAltitudeMsl+","+(Double.isFinite(latestDirectionDegrees)?latestDirectionDegrees:-1)+")",null);}
    @JavascriptInterface public void toggleFullscreen(){runOnUiThread(()->{fullScreenMap=!fullScreenMap;dashboard.setVisibility(fullScreenMap?View.GONE:View.VISIBLE);bottomControls.setVisibility(fullScreenMap?View.GONE:View.VISIBLE);if(mapReady)map.evaluateJavascript("javascript:setFullscreen("+fullScreenMap+")",null);});}
    @JavascriptInterface public void onDirectionChanged(double degrees){latestDirectionDegrees=degrees;}
    @JavascriptInterface public void onMapPointSelected(double lat,double lon){
        int id=elevationQueryId.incrementAndGet();
        elevationLookupExecutor.execute(()->{HttpURLConnection c=null;double elevation=Double.NaN;try{
            URL url=new URL(String.format(Locale.US,"https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f",lat,lon));
            c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestProperty("User-Agent","GPS-Speedometer/1.7");
            int status=c.getResponseCode();if(status==HttpURLConnection.HTTP_OK){try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){StringBuilder body=new StringBuilder();String line;while((line=reader.readLine())!=null)body.append(line);JSONArray values=new JSONObject(body.toString()).getJSONArray("elevation");if(values.length()>0&&!values.isNull(0))elevation=values.getDouble(0);}}
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            final double result=elevation;mainHandler.post(()->{if(id==elevationQueryId.get()&&mapReady)map.evaluateJavascript("javascript:showTappedElevation("+lat+","+lon+","+result+","+(Double.isFinite(result)?1:-1)+")",null);});
        });
    }
    private void convertRecordedPointsToMsl(){for(int i=0;i<points.size();i++){Location point=points.get(i);if(!point.hasMslAltitude()){Location converted=new Location(point);if(Api34Altitude.tryConvert(converted))points.set(i,converted);}}}
    private void toggleTrip(){
        if(!recording){recording=true;paused=false;startedAt=System.currentTimeMillis();activeSegmentStartedAt=startedAt;activeMillis=0;distanceMeters=0;maxKmh=0;points.clear();previousTripPoint=null;recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:clearRoute();resumeGpsFollow()",null);}
        else if(!paused){activeMillis+=System.currentTimeMillis()-activeSegmentStartedAt;paused=true;previousTripPoint=null;recordButton.setText("RESUME TRIP");}
        else {paused=false;activeSegmentStartedAt=System.currentTimeMillis();previousTripPoint=null;recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:resumeGpsFollow()",null);}
        if(!recording)recordButton.setText("START TRIP"); updateTrip();
    }
    private void updateTrip(){tripView.setText(String.format(Locale.getDefault(),"Trip: %.2f km   •   Max: %.0f km/h%s",distanceMeters/1000,maxKmh,paused?"   •   PAUSED":""));}
    private void confirmStop(){if(!recording)return;new AlertDialog.Builder(this).setTitle("Stop and save trip?").setMessage("Save this trip in your history?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}
    private void showHistory(){startActivity(new Intent(this,TripHistoryActivity.class));}
    private void migrateOldHistory(){try{if(getSharedPreferences("gps_trips",0).contains("history"))return;String legacy=getPreferences(0).getString("history",null);if(legacy!=null)getSharedPreferences("gps_trips",0).edit().putString("history",legacy).apply();}catch(Exception ignored){}}
    @Override public void onProviderEnabled(String p){detailView.setText("GPS active • Waiting for location…");}
    @Override public void onProviderDisabled(String p){detailView.setText("GPS is off • Turn on Location in settings.");}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    @Override public void onBackPressed(){ if(recording){new AlertDialog.Builder(this).setTitle("Stop trip?").setMessage("Save this trip before closing?").setNegativeButton("Keep recording",(d,w)->{}).setPositiveButton("Stop and save",(d,w)->saveTrip()).show();}else super.onBackPressed(); }
    private void saveTrip(){
        try{
            long ended=System.currentTimeMillis(); long duration=activeMillis+(paused?0:ended-activeSegmentStartedAt);
            JSONArray route=new JSONArray(); for(Location point:points){JSONObject p=new JSONObject();p.put("lat",point.getLatitude());p.put("lon",point.getLongitude());double alt=Build.VERSION.SDK_INT>=34&&point.hasMslAltitude()?point.getMslAltitudeMeters():point.hasAltitude()?point.getAltitude():Double.NaN;p.put("alt",Double.isFinite(alt)?alt:JSONObject.NULL);p.put("speedKmh",point.hasSpeed()?point.getSpeed()*3.6:JSONObject.NULL);p.put("bearingDeg",point.hasBearing()?point.getBearing():JSONObject.NULL);p.put("time",point.getTime());route.put(p);}
            JSONObject trip=new JSONObject();trip.put("date",new SimpleDateFormat("MMM d, yyyy HH:mm",Locale.getDefault()).format(new Date(startedAt)));trip.put("startTime",startedAt);trip.put("endTime",ended);trip.put("durationMillis",duration);trip.put("distanceMeters",distanceMeters);trip.put("averageKmh",duration>0?(distanceMeters/(duration/1000.0))*3.6:0);trip.put("maxKmh",maxKmh);trip.put("altitudeReference",Build.VERSION.SDK_INT>=34&&altitudeMslReady?"MSL":"WGS84");trip.put("points",route);
            JSONArray old=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));JSONArray saved=new JSONArray();for(int i=Math.max(0,old.length()-29);i<old.length();i++)saved.put(old.get(i));saved.put(trip);getSharedPreferences("gps_trips",0).edit().putString("history",saved.toString()).apply();
        }catch(Exception ignored){}
        recording=false;paused=false;activeMillis=0;previousTripPoint=null;recordButton.setText("START");updateTrip();
    }
    @JavascriptInterface public void stopTrip(){runOnUiThread(()->{if(recording){new AlertDialog.Builder(this).setTitle("Stop and save trip?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}});}
    @Override protected void onDestroy(){super.onDestroy();altitudeWorker.shutdown();elevationLookupExecutor.shutdownNow();stopLocationUpdates();try{if(Build.VERSION.SDK_INT>=24)locationManager.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}}
    private static class Api34Altitude{
        private static final android.location.altitude.AltitudeConverter CONVERTER=new android.location.altitude.AltitudeConverter();
        static boolean prepare(Context context,Location location){try{CONVERTER.addMslAltitudeToLocation(context,location);return location.hasMslAltitude();}catch(Exception e){return false;}}
        static boolean tryConvert(Location location){return CONVERTER.tryAddMslAltitudeToLocation(location);}
    }
}

