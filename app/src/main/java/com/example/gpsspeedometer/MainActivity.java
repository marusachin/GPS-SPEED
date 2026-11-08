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
    private TextView speedView, detailView, tripView, signalView, altitudeView;
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
            satellitesInFix=used; updateSignal(strongest);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(10, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(10, 18, 32));
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
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
        detailView = new TextView(this); detailView.setText("Waiting for GPS • Accuracy —"); detailView.setTextColor(Color.LTGRAY); detailView.setTextSize(13); detailView.setGravity(Gravity.CENTER); detailView.setPadding(8,dp(2),8,dp(8)); dashboardAdd(detailView);
        map = new WebView(this); map.setBackgroundColor(Color.rgb(30,42,55)); map.getSettings().setJavaScriptEnabled(true); map.setWebChromeClient(new WebChromeClient()); map.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String u){mapReady=true;}}); map.addJavascriptInterface(this,"Android"); map.loadUrl("file:///android_asset/map.html");
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
    private void startGps(){ try { if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return; locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); if(Build.VERSION.SDK_INT>=24)locationManager.registerGnssStatusCallback(gnssCallback,new Handler(Looper.getMainLooper())); detailView.setText("GPS on • Waiting for a satellite fix…"); } catch(Exception e){detailView.setText("Turn on Location in phone settings.");} }
    private void updateSignal(float cn0){ if(signalView==null)return; String label=satellitesInFix==0?"● GPS searching":satellitesInFix+" satellites • "+(cn0>=30?"Strong":cn0>=20?"Fair":"Weak"); signalView.setText(label);signalView.setTextColor(satellitesInFix==0?Color.rgb(255,190,80):cn0>=20?Color.rgb(90,220,140):Color.rgb(255,190,80)); }
    @Override public void onLocationChanged(Location loc){
        float kmh=loc.hasSpeed()?loc.getSpeed()*3.6f:0; speedView.setText(String.format(Locale.getDefault(),"%.0f",kmh));
        boolean gps=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        detailView.setText((gps?"GPS fix":"GPS off")+" • Accuracy ±"+String.format(Locale.getDefault(),"%.0f",loc.getAccuracy())+" m");
        prepareAltitude(loc);
        latestSpeedKmh=kmh;sessionMaxKmh=Math.max(sessionMaxKmh,kmh);
        if(recording && !paused){ if(previousTripPoint!=null)distanceMeters+=previousTripPoint.distanceTo(loc); previousTripPoint=new Location(loc); if(points.size()<6000)points.add(new Location(loc)); maxKmh=Math.max(maxKmh,kmh); }
        updateTrip(); if(mapReady){double bearing=loc.hasBearing()?loc.getBearing():-1;double bearingAccuracy=Build.VERSION.SDK_INT>=26&&loc.hasBearingAccuracy()?loc.getBearingAccuracyDegrees():-1;double speed=loc.hasSpeed()?loc.getSpeed():0;String js="javascript:setPoint("+loc.getLatitude()+","+loc.getLongitude()+","+loc.getAccuracy()+","+bearing+","+speed+","+bearingAccuracy+")";map.evaluateJavascript(js,null);updateMapInfo();}
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
    private void updateMapInfo(){if(mapReady)map.evaluateJavascript("javascript:setMapInfo("+latestSpeedKmh+","+Math.max(maxKmh,sessionMaxKmh)+","+latestAltitudeMeters+","+latestAltitudeKnown+","+latestAltitudeMsl+","+(Double.isFinite(latestDirectionDegrees)?latestDirectionDegrees:-1)+")",null);}
    @JavascriptInterface public void toggleFullscreen(){runOnUiThread(()->{fullScreenMap=!fullScreenMap;dashboard.setVisibility(fullScreenMap?View.GONE:View.VISIBLE);bottomControls.setVisibility(fullScreenMap?View.GONE:View.VISIBLE);if(mapReady)map.evaluateJavascript("javascript:setFullscreen("+fullScreenMap+")",null);});}
    @JavascriptInterface public void onDirectionChanged(double degrees){latestDirectionDegrees=degrees;}
    @JavascriptInterface public void onMapPointSelected(double lat,double lon){int id=elevationQueryId.incrementAndGet();map.evaluateJavascript("javascript:showTappedElevation("+lat+","+lon+",0,false)",null);elevationLookupExecutor.execute(()->{HttpURLConnection c=null;double elevation=Double.NaN;try{URL url=new URL(String.format(Locale.US,"https://api.open-meteo.com/v1/elevation?latitude=%.6f&longitude=%.6f",lat,lon));c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream()))){StringBuilder body=new StringBuilder();String line;while((line=reader.readLine())!=null)body.append(line);JSONArray values=new JSONObject(body.toString()).getJSONArray("elevation");if(values.length()>0&&!values.isNull(0))elevation=values.getDouble(0);}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}final double result=elevation;mainHandler.post(()->{if(id==elevationQueryId.get()&&mapReady)map.evaluateJavascript("javascript:showTappedElevation("+lat+","+lon+","+result+","+Double.isFinite(result)+")",null);});});}
    private void convertRecordedPointsToMsl(){for(int i=0;i<points.size();i++){Location point=points.get(i);if(!point.hasMslAltitude()){Location converted=new Location(point);if(Api34Altitude.tryConvert(converted))points.set(i,converted);}}}
    private void toggleTrip(){
        if(!recording){recording=true;paused=false;startedAt=System.currentTimeMillis();activeSegmentStartedAt=startedAt;activeMillis=0;distanceMeters=0;maxKmh=0;points.clear();previousTripPoint=null;recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:clearRoute()",null);}
        else if(!paused){activeMillis+=System.currentTimeMillis()-activeSegmentStartedAt;paused=true;previousTripPoint=null;recordButton.setText("RESUME TRIP");}
        else {paused=false;activeSegmentStartedAt=System.currentTimeMillis();previousTripPoint=null;recordButton.setText("PAUSE TRIP");}
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
            JSONArray route=new JSONArray(); for(Location point:points){JSONObject p=new JSONObject();p.put("lat",point.getLatitude());p.put("lon",point.getLongitude());double alt=Build.VERSION.SDK_INT>=34&&point.hasMslAltitude()?point.getMslAltitudeMeters():point.hasAltitude()?point.getAltitude():Double.NaN;p.put("alt",Double.isFinite(alt)?alt:JSONObject.NULL);p.put("time",point.getTime());route.put(p);}
            JSONObject trip=new JSONObject();trip.put("date",new SimpleDateFormat("MMM d, yyyy HH:mm",Locale.getDefault()).format(new Date(startedAt)));trip.put("startTime",startedAt);trip.put("endTime",ended);trip.put("durationMillis",duration);trip.put("distanceMeters",distanceMeters);trip.put("averageKmh",duration>0?(distanceMeters/(duration/1000.0))*3.6:0);trip.put("maxKmh",maxKmh);trip.put("altitudeReference",Build.VERSION.SDK_INT>=34&&altitudeMslReady?"MSL":"WGS84");trip.put("points",route);
            JSONArray old=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));JSONArray saved=new JSONArray();for(int i=Math.max(0,old.length()-29);i<old.length();i++)saved.put(old.get(i));saved.put(trip);getSharedPreferences("gps_trips",0).edit().putString("history",saved.toString()).apply();
        }catch(Exception ignored){}
        recording=false;paused=false;activeMillis=0;previousTripPoint=null;recordButton.setText("START");updateTrip();
    }
    @JavascriptInterface public void stopTrip(){runOnUiThread(()->{if(recording){new AlertDialog.Builder(this).setTitle("Stop and save trip?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}});}
    @Override protected void onDestroy(){super.onDestroy();altitudeWorker.shutdown();elevationLookupExecutor.shutdownNow();try{locationManager.removeUpdates(this);if(Build.VERSION.SDK_INT>=24)locationManager.unregisterGnssStatusCallback(gnssCallback);}catch(Exception ignored){}}
    private static class Api34Altitude{
        private static final android.location.altitude.AltitudeConverter CONVERTER=new android.location.altitude.AltitudeConverter();
        static boolean prepare(Context context,Location location){try{CONVERTER.addMslAltitudeToLocation(context,location);return location.hasMslAltitude();}catch(Exception e){return false;}}
        static boolean tryConvert(Location location){return CONVERTER.tryAddMslAltitudeToLocation(location);}
    }
}

