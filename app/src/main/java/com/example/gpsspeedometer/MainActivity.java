package com.example.gpsspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity implements LocationListener {
    private static final int REQUEST_LOCATION = 10;
    private static final int REQUEST_TRIP_FOLDER = 52;
    private JSONObject tripPendingFolderExport;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private double latestCompassDegrees=Double.NaN;
    private long lastCompassUpdateMs=0;
    private float latestAccuracyMeters = Float.NaN;
    private Location lastAcceptedMapLocation;
    private float smoothedSpeedKmh = 0;
    private long lastAcceptedMovementAt = 0;
    private TextView speedView, detailView, tripView, signalView, altitudeView;
    private WebView map;
    private View dashboard;
    private LinearLayout bottomControls;
    private Button recordButton;
    private String selectedTravelMode="car";
    private boolean fullScreenMap = false;
    private boolean mapReady = false, recording = false, paused = false;
    private final ArrayList<Location> points = new ArrayList<>();
    private float maxKmh = 0;
    private long startedAt = 0;
    private double distanceMeters = 0;
    private long activeMillis = 0, activeSegmentStartedAt = 0;
    private long idleMillis = 0, lastTripFixAt = 0;
    private boolean lastTripFixWasIdle = false;
    private Location previousTripPoint = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService altitudeWorker = Executors.newSingleThreadExecutor();
    private Location latestAltitudeLocation;
    private boolean altitudeWarmupRunning = false, altitudeMslReady = false;
    private double latestSpeedKmh=0, sessionMaxKmh=0, latestAltitudeMeters=0;
    private boolean latestAltitudeKnown=false, latestAltitudeMsl=false;
    private double latestDirectionDegrees=Double.NaN;
    private final ExecutorService elevationLookupExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService offlineTileExecutor=Executors.newFixedThreadPool(4);
    private final ExecutorService offlineCoordinator=Executors.newSingleThreadExecutor();
    private static final long OFFLINE_MAX_BYTES=50L*1024L*1024L;
    private static final int OFFLINE_OPENFREEMAP_TILE_LIMIT=3000;
    private final Set<String> offlineTileUrls=ConcurrentHashMap.newKeySet();
    private final AtomicInteger elevationQueryId=new AtomicInteger(0);
    private int satellitesInFix = 0;
    private final SensorEventListener compassListener = new SensorEventListener() {
        private final float[] rotationMatrix = new float[9];
        private final float[] orientation = new float[3];
        @Override public void onSensorChanged(SensorEvent event) {
            if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;
            SensorManager.getRotationMatrixFromVector(rotationMatrix,event.values);
            SensorManager.getOrientation(rotationMatrix,orientation);
            double degrees=(Math.toDegrees(orientation[0])+360.0)%360.0;
            long now=System.currentTimeMillis();
            if(event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE||now-lastCompassUpdateMs<75)return;
            latestCompassDegrees=degrees;
            lastCompassUpdateMs=now;
            if(mapReady)map.evaluateJavascript("javascript:setCompassHeading("+degrees+","+event.accuracy+")",null);
        }
        @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}
    };
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
        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager!=null?sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR):null;
        migrateOldHistory();
        buildUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        else startGps();
    }

    private void buildUi() {
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this); root.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(10,18,32));
        dashboard=new LinearLayout(this);((LinearLayout)dashboard).setOrientation(LinearLayout.VERTICAL);root.addView(dashboard,landscape?new LinearLayout.LayoutParams(dp(230),-1):new LinearLayout.LayoutParams(-1,-2));
        root.setOnApplyWindowInsetsListener((v,insets)->{int top=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(dp(12),top+dp(4),dp(12),bottom+dp(8));return insets;});
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(4,dp(6),4,dp(4));
        TextView title = new TextView(this); title.setText("GPS SPEEDOMETER"); title.setTextColor(Color.WHITE); title.setTextSize(15); title.setSingleLine(true); title.setGravity(Gravity.CENTER_VERTICAL); title.setTypeface(null,android.graphics.Typeface.BOLD); header.addView(title,new LinearLayout.LayoutParams(0,dp(38),1));
        signalView = new TextView(this); signalView.setText("● GPS searching"); signalView.setTextColor(Color.rgb(255,190,80)); signalView.setTextSize(11); signalView.setSingleLine(true); signalView.setGravity(Gravity.CENTER_VERTICAL); header.addView(signalView); dashboardAdd(header);
        LinearLayout modeRow=new LinearLayout(this);modeRow.setGravity(Gravity.CENTER_VERTICAL);modeRow.setPadding(dp(4),0,dp(4),0);TextView modeLabel=new TextView(this);modeLabel.setText("Travel mode");modeLabel.setTextColor(Color.LTGRAY);modeLabel.setTextSize(13);modeRow.addView(modeLabel,new LinearLayout.LayoutParams(0,dp(40),1));
        Spinner modeSpinner=new Spinner(this);String[] modeOptions={"🚆 Train","🚗 Car","🛵 Scooter","🚴 Cycle"};String[] modeCodes={"train","car","scooter","cycle"};ArrayAdapter<String> modeAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,modeOptions){@Override public View getView(int position,View convertView,android.view.ViewGroup parent){TextView item=(TextView)super.getView(position,convertView,parent);item.setTextColor(Color.WHITE);item.setTextSize(14);return item;}@Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){TextView item=(TextView)super.getDropDownView(position,convertView,parent);item.setTextColor(Color.WHITE);item.setBackgroundColor(Color.rgb(24,35,51));item.setTextSize(14);return item;}};modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);modeSpinner.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35,47,64)));modeSpinner.setAdapter(modeAdapter);int savedMode=getSharedPreferences("gps_settings",0).getInt("travel_mode",1);modeSpinner.setSelection(Math.max(0,Math.min(modeOptions.length-1,savedMode)));modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){selectedTravelMode=modeCodes[position];getSharedPreferences("gps_settings",0).edit().putInt("travel_mode",position).apply();if(mapReady)map.evaluateJavascript("javascript:setTravelMode("+JSONObject.quote(selectedTravelMode)+")",null);}@Override public void onNothingSelected(AdapterView<?> parent){}});modeRow.addView(modeSpinner,new LinearLayout.LayoutParams(dp(150),dp(42)));dashboardAdd(modeRow);
        speedView = new TextView(this); speedView.setText("0"); speedView.setTextColor(Color.WHITE); speedView.setTextSize(62); speedView.setGravity(Gravity.CENTER); dashboardAdd(speedView,new LinearLayout.LayoutParams(-1,dp(84)));
        TextView units = new TextView(this); units.setText("km/h"); units.setTextColor(Color.rgb(90,190,255)); units.setTextSize(16); units.setGravity(Gravity.CENTER); dashboardAdd(units,new LinearLayout.LayoutParams(-1,dp(25)));
        altitudeView = new TextView(this); altitudeView.setText("GPS altitude — m"); altitudeView.setTextColor(Color.WHITE); altitudeView.setTextSize(12); altitudeView.setGravity(Gravity.CENTER); altitudeView.setPadding(0,dp(2),0,dp(2)); dashboardAdd(altitudeView,new LinearLayout.LayoutParams(-1,dp(24)));
        detailView = new TextView(this); detailView.setText("Waiting for location • Accuracy —"); detailView.setTextColor(Color.LTGRAY); detailView.setTextSize(13); detailView.setGravity(Gravity.CENTER); detailView.setPadding(8,dp(2),8,dp(2)); dashboardAdd(detailView);
        map = new WebView(this); map.setBackgroundColor(Color.rgb(30,42,55)); map.getSettings().setJavaScriptEnabled(true); map.getSettings().setDomStorageEnabled(true); map.getSettings().setUserAgentString(map.getSettings().getUserAgentString()+" GPS-Speedometer/2.0"); map.setWebChromeClient(new WebChromeClient()); map.setWebViewClient(new WebViewClient(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){return interceptMapRequest(request.getUrl().toString());}@Override public void onPageFinished(WebView v,String u){mapReady=true;map.evaluateJavascript("javascript:setTravelMode("+JSONObject.quote(selectedTravelMode)+")",null);updateMapGpsInfo();}}); map.addJavascriptInterface(this,"Android"); map.loadUrl("file:///android_asset/map.html");
        root.addView(map,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        bottomControls = new LinearLayout(this); bottomControls.setOrientation(LinearLayout.VERTICAL);
        if(landscape)((LinearLayout)dashboard).addView(bottomControls,new LinearLayout.LayoutParams(-1,-2));else root.addView(bottomControls,new LinearLayout.LayoutParams(-1,-2));
        tripView = new TextView(this); tripView.setText("Trip: 0.00 km   •   Max: 0 km/h"); tripView.setTextColor(Color.WHITE); tripView.setTextSize(15); tripView.setGravity(Gravity.CENTER); tripView.setPadding(4,dp(8),4,dp(8)); bottomControls.addView(tripView,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout buttons = new LinearLayout(this); buttons.setPadding(0,dp(2),0,dp(6)); buttons.setGravity(Gravity.CENTER); buttons.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        LinearLayout firstButtonRow=buttons,secondButtonRow=null;
        if(landscape){firstButtonRow=new LinearLayout(this);firstButtonRow.setOrientation(LinearLayout.HORIZONTAL);secondButtonRow=new LinearLayout(this);secondButtonRow.setOrientation(LinearLayout.HORIZONTAL);buttons.addView(firstButtonRow,new LinearLayout.LayoutParams(-1,dp(44)));buttons.addView(secondButtonRow,new LinearLayout.LayoutParams(-1,dp(44)));}
        recordButton = button("START"); recordButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34,160,88))); recordButton.setOnClickListener(v -> toggleTrip()); firstButtonRow.addView(recordButton,new LinearLayout.LayoutParams(0,dp(landscape?42:48),1));
        Button stop = button("STOP"); stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(220,53,69))); stop.setOnClickListener(v -> confirmStop()); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,dp(landscape?42:48),1); if(!landscape)sp.leftMargin=dp(6);firstButtonRow.addView(stop,sp);
        Button history = button("HISTORY"); history.setOnClickListener(v -> showHistory()); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0,dp(landscape?42:48),1); if(landscape)hp.leftMargin=dp(4);else hp.leftMargin=dp(6); (landscape?secondButtonRow:buttons).addView(history,hp);
        Button satellites = button("SATELLITES"); satellites.setTextSize(landscape?9:10); satellites.setOnClickListener(v -> startActivity(new Intent(this,SatelliteActivity.class))); LinearLayout.LayoutParams satp = new LinearLayout.LayoutParams(0,dp(landscape?42:48),1); satp.leftMargin=dp(landscape?4:6); (landscape?secondButtonRow:buttons).addView(satellites,satp);
        bottomControls.addView(buttons);
        setContentView(root);
    }
    private void dashboardAdd(View view){((LinearLayout)dashboard).addView(view);}
    private void dashboardAdd(View view,LinearLayout.LayoutParams params){((LinearLayout)dashboard).addView(view,params);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextSize(13); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(3),0,dp(3),0); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24,116,210))); return b; }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){ super.onRequestPermissionsResult(requestCode,permissions,results); if(requestCode==REQUEST_LOCATION){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startGps();else detailView.setText("Location permission is needed for GPS speed and route.");} }
    private void startGps(){ try { if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return; locationManager.removeUpdates(this); locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); if(Build.VERSION.SDK_INT>=24){try{locationManager.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){} locationManager.registerGnssStatusCallback(gnssCallback,new Handler(Looper.getMainLooper()));} detailView.setText("GPS only • Waiting for a satellite fix…"); } catch(Exception e){detailView.setText("Turn on Location in phone settings.");} }
    private void stopLocationUpdates(){ try{locationManager.removeUpdates(this);}catch(Exception ignored){} }
    private void updateSignal(float cn0){ if(signalView==null)return; String label=satellitesInFix==0?"● GPS searching":satellitesInFix+" satellites • "+(cn0>=30?"Strong":cn0>=20?"Fair":"Weak"); signalView.setText(label);signalView.setTextColor(satellitesInFix==0?Color.rgb(255,190,80):cn0>=20?Color.rgb(90,220,140):Color.rgb(255,190,80)); }
    @Override public void onLocationChanged(Location loc){
        if(loc==null)return;
        long now=System.currentTimeMillis();
        float accuracy=loc.hasAccuracy()?loc.getAccuracy():Float.POSITIVE_INFINITY;
        latestAccuracyMeters=accuracy;
        boolean gps=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        String fixLabel=accuracy>35f?"Weak GPS fix":(gps?"GPS fix":"GPS off");
        String accuracyLabel=(!Float.isNaN(accuracy)&&!Float.isInfinite(accuracy))?String.format(Locale.getDefault()," • Accuracy ±%.0f m",accuracy):" • Accuracy unavailable";
        detailView.setText(fixLabel+accuracyLabel);
        updateMapGpsInfo();

        if(Float.isNaN(accuracy)||Float.isInfinite(accuracy)||accuracy>35f){
            if(now-lastAcceptedMovementAt>2500)smoothedSpeedKmh=0;
            latestSpeedKmh=smoothedSpeedKmh;
            updateIdleTime(loc,accuracy);
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
        updateIdleTime(loc,accuracy);
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
    private void updateMapGpsInfo(){if(mapReady&&!Float.isNaN(latestAccuracyMeters)&&!Float.isInfinite(latestAccuracyMeters))map.evaluateJavascript("javascript:setGpsInfo("+latestAccuracyMeters+","+satellitesInFix+")",null);}
    private void updateMapInfo(){if(mapReady)map.evaluateJavascript("javascript:setMapInfo("+latestSpeedKmh+","+(recording?maxKmh:sessionMaxKmh)+","+latestAltitudeMeters+","+latestAltitudeKnown+","+latestAltitudeMsl+","+(Double.isFinite(latestDirectionDegrees)?latestDirectionDegrees:-1)+")",null);}
    private File mapCacheDir(){File dir=new File(getFilesDir(),"offline_map_tiles");if(!dir.exists())dir.mkdirs();return dir;}
    private File cacheFile(String url){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format(Locale.US,"%02x",b));return new File(mapCacheDir(),name.toString()+".tile");}catch(Exception e){return null;}}
    private String mapMime(String url){if(url.endsWith(".pbf"))return "application/x-protobuf";if(url.endsWith(".webp"))return "image/webp";if(url.endsWith(".png"))return "image/png";if(url.endsWith(".json")||url.contains("/styles/"))return "application/json";if(url.endsWith(".woff2"))return "font/woff2";return "application/octet-stream";}
    private WebResourceResponse cachedResponse(String url,InputStream data,String mime){java.util.Map<String,String> headers=new java.util.HashMap<>();headers.put("Access-Control-Allow-Origin","*");headers.put("Cache-Control","public, max-age=604800");return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,data);}
    private WebResourceResponse interceptMapRequest(String url){if(!(url.startsWith("https://tiles.openfreemap.org/")||url.startsWith("https://tiles.mapterhorn.com/")||url.startsWith("https://unpkg.com/maplibre-gl@5.6.0/")))return null;File f=cacheFile(url);try{if(f!=null&&f.exists()&&f.length()>0)return cachedResponse(url,new FileInputStream(f),mapMime(url));HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(9000);conn.setReadTimeout(15000);conn.setRequestProperty("User-Agent","GPS-Speedometer/2.18 (Android; offline maps)");int code=conn.getResponseCode();if(code!=200){conn.disconnect();return null;}byte[] bytes;try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>8*1024*1024)break;}bytes=out.toByteArray();}String mime=conn.getContentType();conn.disconnect();if(f!=null&&bytes.length>0){try(OutputStream out=new java.io.FileOutputStream(f)){out.write(bytes);}}return cachedResponse(url,new java.io.ByteArrayInputStream(bytes),mime==null?mapMime(url):mime.split(";")[0]);}catch(Exception ignored){return null;}}
    private int tileX(double lon,int z){int n=1<<z;return Math.max(0,Math.min(n-1,(int)Math.floor((lon+180.0)/360.0*n)));}
    private int tileY(double lat,int z){int n=1<<z;double r=Math.toRadians(Math.max(-85.0511,Math.min(85.0511,lat)));int y=(int)Math.floor((1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n);return Math.max(0,Math.min(n-1,y));}
    private byte[] fetchTile(String url)throws Exception{File f=cacheFile(url);if(f!=null&&f.exists()&&f.length()>0){try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();}}HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(10000);conn.setReadTimeout(15000);conn.setRequestProperty("User-Agent","GPS-Speedometer/2.18 (Android; offline maps)");try{if(conn.getResponseCode()!=200)throw new java.io.IOException("HTTP "+conn.getResponseCode());try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4*1024*1024)throw new java.io.IOException("Tile too large");}byte[] bytes=out.toByteArray();if(f!=null)try(OutputStream saved=new java.io.FileOutputStream(f)){saved.write(bytes);}return bytes;}}finally{conn.disconnect();}}
    private void offlineStatus(int done,int total,String status){mainHandler.post(()->{if(mapReady)map.evaluateJavascript("javascript:onOfflineDownloadProgress("+done+","+total+","+JSONObject.quote(status)+")",null);});}
    @JavascriptInterface public void downloadOfflineArea(double south,double west,double north,double east,int minZoom,int maxZoom){offlineCoordinator.execute(()->{try{if(!Double.isFinite(south)||!Double.isFinite(west)||!Double.isFinite(north)||!Double.isFinite(east)||south>=north||west>=east){offlineStatus(0,0,"Zoom to a smaller area and try again");return;}ArrayList<String> vector=new ArrayList<>(),terrain=new ArrayList<>();for(int z=Math.max(5,minZoom);z<=Math.min(14,maxZoom);z++){int x0=tileX(west,z),x1=tileX(east,z),y0=tileY(north,z),y1=tileY(south,z);long count=(long)(x1-x0+1)*(y1-y0+1);if(count+vector.size()>OFFLINE_OPENFREEMAP_TILE_LIMIT){offlineStatus(0,0,"Area too large; zoom in to stay under 3,000 map tiles");return;}for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++){String path=z+"/"+x+"/"+y;vector.add("https://tiles.openfreemap.org/planet/latest/"+path+".pbf");if(z<=14)terrain.add("https://tiles.mapterhorn.com/"+path+".webp");}}ArrayList<String> urls=new ArrayList<>(vector);urls.addAll(terrain);CountDownLatch latch=new CountDownLatch(urls.size());AtomicInteger done=new AtomicInteger(0),failed=new AtomicInteger(0);AtomicLong bytes=new AtomicLong(0);java.util.concurrent.atomic.AtomicBoolean tooLarge=new java.util.concurrent.atomic.AtomicBoolean(false);offlineStatus(0,urls.size(),"Downloading map and terrain…");for(String url:urls)offlineTileExecutor.submit(()->{try{if(tooLarge.get())return;byte[] data=fetchTile(url);long total=bytes.addAndGet(data.length);if(total>OFFLINE_MAX_BYTES){tooLarge.set(true);return;}offlineTileUrls.add(url);done.incrementAndGet();}catch(Exception e){failed.incrementAndGet();}finally{latch.countDown();offlineStatus(done.get(),urls.size(),"Downloading map and terrain…");}});latch.await();File index=new File(mapCacheDir(),"offline-area-urls.txt");try(java.io.BufferedWriter out=new java.io.BufferedWriter(new java.io.FileWriter(index,true))){for(String url:offlineTileUrls){out.write(url);out.newLine();}}String result=tooLarge.get()?"Stopped at the 50 MB limit; partial area saved":failed.get()>0?"Partial offline map saved; "+failed.get()+" tiles unavailable":"Offline map complete • "+(bytes.get()/1024/1024)+" MB";offlineStatus(done.get(),urls.size(),result);}catch(Exception e){offlineStatus(0,0,"Offline download failed; check internet and storage");}});}
    @JavascriptInterface public void deleteOfflineMaps(){offlineCoordinator.execute(()->{File index=new File(mapCacheDir(),"offline-area-urls.txt");int removed=0;if(index.exists())try(BufferedReader in=new BufferedReader(new java.io.FileReader(index))){String url;while((url=in.readLine())!=null){File f=cacheFile(url);if(f!=null&&f.delete())removed++;}}catch(Exception ignored){}if(index.exists())index.delete();offlineTileUrls.clear();offlineStatus(0,0,removed>0?"Downloaded offline map deleted":"No offline map saved");});}
    @JavascriptInterface public void toggleRecording(){runOnUiThread(()->toggleTrip());}
    @JavascriptInterface public void stopRecording(){runOnUiThread(()->confirmStop());}
    @JavascriptInterface public void openHistory(){runOnUiThread(()->showHistory());}
    @JavascriptInterface public void openSatelliteInfo(){runOnUiThread(()->startActivity(new Intent(this,SatelliteActivity.class)));}
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
    private void startTripRecordingService(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},41);
        Intent service=new Intent(this,TripRecordingService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
    }
    private void stopTripRecordingService(){stopService(new Intent(this,TripRecordingService.class));}

    private void updateIdleTime(Location loc,float accuracy){
        if(!recording||paused||accuracy>35f){lastTripFixAt=0;lastTripFixWasIdle=false;return;}
        long sample=loc.getTime()>0?loc.getTime():System.currentTimeMillis();
        if(lastTripFixAt>0&&lastTripFixWasIdle){long delta=sample-lastTripFixAt;if(delta>0)idleMillis+=Math.min(delta,5000);}
        lastTripFixAt=sample;
        lastTripFixWasIdle=latestSpeedKmh<1.0;
    }
    private void flushIdleTime(long now){
        if(recording&&!paused&&lastTripFixAt>0&&lastTripFixWasIdle){long delta=now-lastTripFixAt;if(delta>0)idleMillis+=Math.min(delta,5000);}
        lastTripFixAt=0;lastTripFixWasIdle=false;
    }
    private void toggleTrip(){
        if(!recording){recording=true;paused=false;startedAt=System.currentTimeMillis();activeSegmentStartedAt=startedAt;activeMillis=0;idleMillis=0;lastTripFixAt=0;lastTripFixWasIdle=false;distanceMeters=0;maxKmh=0;points.clear();previousTripPoint=null;recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:clearRoute();resumeGpsFollow()",null);}
        else if(!paused){activeMillis+=System.currentTimeMillis()-activeSegmentStartedAt;flushIdleTime(System.currentTimeMillis());paused=true;previousTripPoint=null;recordButton.setText("RESUME TRIP");}
        else {paused=false;activeSegmentStartedAt=System.currentTimeMillis();lastTripFixAt=0;lastTripFixWasIdle=false;previousTripPoint=null;recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:resumeGpsFollow()",null);}
        if(!recording)recordButton.setText("START TRIP");
        if(mapReady)map.evaluateJavascript("javascript:updateRecordButtons("+JSONObject.quote(recordButton.getText().toString())+")",null);
        if(recording&&!paused)startTripRecordingService();else stopTripRecordingService();
        updateTrip();
    }
    private void updateTrip(){
        tripView.setText(String.format(Locale.getDefault(),"Trip: %.2f km   •   Max: %.0f km/h%s",distanceMeters/1000,maxKmh,paused?"   •   PAUSED":""));
        if(recording&&!paused){
            Intent update=new Intent(this,TripRecordingService.class);
            update.setAction(TripRecordingService.ACTION_UPDATE);
            update.putExtra("distance_km",distanceMeters/1000.0);
            update.putExtra("speed_kmh",latestSpeedKmh);
            startService(update);
        }
    }
    private void confirmStop(){if(!recording)return;new AlertDialog.Builder(this).setTitle("Stop and save trip?").setMessage("Save this trip in your history?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}
    private void showHistory(){startActivity(new Intent(this,TripHistoryActivity.class));}
    private void migrateOldHistory(){try{if(getSharedPreferences("gps_trips",0).contains("history"))return;String legacy=getPreferences(0).getString("history",null);if(legacy!=null)getSharedPreferences("gps_trips",0).edit().putString("history",legacy).apply();}catch(Exception ignored){}}
    @Override public void onProviderEnabled(String p){detailView.setText("GPS active • Waiting for location…");}
    @Override public void onProviderDisabled(String p){detailView.setText("GPS is off • Turn on Location in settings.");}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    @Override public void onBackPressed(){ if(recording){new AlertDialog.Builder(this).setTitle("Stop trip?").setMessage("Save this trip before closing?").setNegativeButton("Keep recording",(d,w)->{}).setPositiveButton("Stop and save",(d,w)->saveTrip()).show();}else super.onBackPressed(); }
    private void saveTrip(){
        JSONObject tripForFolder=null;
        try{
            long ended=System.currentTimeMillis();if(!paused)flushIdleTime(ended);long duration=activeMillis+(paused?0:ended-activeSegmentStartedAt);
            JSONArray route=new JSONArray(); for(Location point:points){JSONObject p=new JSONObject();p.put("lat",point.getLatitude());p.put("lon",point.getLongitude());double alt=Build.VERSION.SDK_INT>=34&&point.hasMslAltitude()?point.getMslAltitudeMeters():point.hasAltitude()?point.getAltitude():Double.NaN;p.put("alt",Double.isFinite(alt)?alt:JSONObject.NULL);p.put("speedKmh",point.hasSpeed()?point.getSpeed()*3.6:JSONObject.NULL);p.put("bearingDeg",point.hasBearing()?point.getBearing():JSONObject.NULL);p.put("time",point.getTime());route.put(p);}
            JSONObject trip=new JSONObject();trip.put("date",new SimpleDateFormat("MMM d, yyyy HH:mm",Locale.getDefault()).format(new Date(startedAt)));trip.put("startTime",startedAt);trip.put("endTime",ended);trip.put("durationMillis",duration);trip.put("distanceMeters",distanceMeters);trip.put("averageKmh",duration>0?(distanceMeters/(duration/1000.0))*3.6:0);trip.put("maxKmh",maxKmh);trip.put("idleMillis",idleMillis);trip.put("altitudeReference",Build.VERSION.SDK_INT>=34&&altitudeMslReady?"MSL":"WGS84");trip.put("points",route);
            tripForFolder=trip;JSONArray old=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));JSONArray saved=new JSONArray();for(int i=0;i<old.length();i++)saved.put(old.get(i));saved.put(trip);getSharedPreferences("gps_trips",0).edit().putString("history",saved.toString()).apply();
        }catch(Exception ignored){}
        stopTripRecordingService();recording=false;paused=false;activeMillis=0;previousTripPoint=null;recordButton.setText("START");updateTrip();
        saveTripCopyToFolder(tripForFolder);
    }
    private android.net.Uri savedTripFolder(){String value=getSharedPreferences("gps_backup",0).getString("folder",null);try{return value==null?null:Uri.parse(value);}catch(Exception e){return null;}}
    private void saveTripCopyToFolder(JSONObject trip){if(trip==null)return;Uri tree=savedTripFolder();if(tree==null){tripPendingFolderExport=trip;new AlertDialog.Builder(this).setTitle("Choose a trip folder?").setMessage("This trip is saved in Trip History. Choose a folder to also save a backup file there automatically after each trip.").setNegativeButton("Later",(d,w)->tripPendingFolderExport=null).setPositiveButton("Choose folder",(d,w)->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(intent,REQUEST_TRIP_FOLDER);}catch(Exception e){tripPendingFolderExport=null;}}).show();return;}writeTripCopyToFolder(trip,tree);}
    private void writeTripCopyToFolder(JSONObject trip,Uri tree){try{String parentId=DocumentsContract.getTreeDocumentId(tree);Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,parentId);String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date(trip.optLong("startTime",System.currentTimeMillis())));Uri file=DocumentsContract.createDocument(getContentResolver(),parent,"application/json","GPS-Trip-"+stamp+".json");if(file==null)throw new java.io.IOException("Unable to create trip file");try(OutputStream out=getContentResolver().openOutputStream(file)){if(out==null)throw new java.io.IOException("Unable to open trip file");JSONArray oneTrip=new JSONArray();oneTrip.put(trip);out.write(oneTrip.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();}Toast.makeText(this,"Trip copy saved in your chosen folder",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Trip is saved in History, but its folder copy failed. Choose the folder again in Import / Export.",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_TRIP_FOLDER||resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri tree=data.getData();try{getContentResolver().takePersistableUriPermission(tree,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));getSharedPreferences("gps_backup",0).edit().putString("folder",tree.toString()).apply();JSONObject trip=tripPendingFolderExport;tripPendingFolderExport=null;if(trip!=null)writeTripCopyToFolder(trip,tree);Toast.makeText(this,"Save folder selected. New trips will be copied here.",Toast.LENGTH_LONG).show();}catch(Exception e){tripPendingFolderExport=null;Toast.makeText(this,"Could not remember that folder. Please choose another.",Toast.LENGTH_LONG).show();}}
    @JavascriptInterface public void stopTrip(){runOnUiThread(()->{if(recording){new AlertDialog.Builder(this).setTitle("Stop and save trip?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}});}
    @Override protected void onResume(){super.onResume();if(sensorManager!=null&&rotationVectorSensor!=null)sensorManager.registerListener(compassListener,rotationVectorSensor,SensorManager.SENSOR_DELAY_UI);}
    @Override protected void onPause(){if(sensorManager!=null)sensorManager.unregisterListener(compassListener);super.onPause();}
    @Override protected void onDestroy(){super.onDestroy();altitudeWorker.shutdown();elevationLookupExecutor.shutdownNow();stopLocationUpdates();try{if(Build.VERSION.SDK_INT>=24)locationManager.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}}
    private static class Api34Altitude{
        private static final android.location.altitude.AltitudeConverter CONVERTER=new android.location.altitude.AltitudeConverter();
        static boolean prepare(Context context,Location location){try{CONVERTER.addMslAltitudeToLocation(context,location);return location.hasMslAltitude();}catch(Exception e){return false;}}
        static boolean tryConvert(Location location){return CONVERTER.tryAddMslAltitudeToLocation(location);}
    }
}

