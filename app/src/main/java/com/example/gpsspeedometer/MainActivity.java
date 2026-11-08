package com.example.gpsspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
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

public class MainActivity extends Activity implements LocationListener {
    private static final int REQUEST_LOCATION = 10;
    private LocationManager locationManager;
    private TextView speedView, detailView, tripView;
    private WebView map;
    private Button recordButton;
    private boolean mapReady = false, recording = false, paused = false;
    private final ArrayList<Location> points = new ArrayList<>();
    private float maxKmh = 0;
    private long startedAt = 0;
    private double distanceMeters = 0;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(10, 18, 32));
        getWindow().setNavigationBarColor(Color.rgb(10, 18, 32));
        locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        buildUi();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        else startGps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(10,18,32));
        TextView title = new TextView(this); title.setText("GPS SPEEDOMETER"); title.setTextColor(Color.WHITE); title.setTextSize(17); title.setGravity(Gravity.CENTER); title.setPadding(8,14,8,4); root.addView(title);
        speedView = new TextView(this); speedView.setText("0"); speedView.setTextColor(Color.WHITE); speedView.setTextSize(66); speedView.setGravity(Gravity.CENTER); speedView.setPadding(0,0,0,0); root.addView(speedView);
        TextView units = new TextView(this); units.setText("km/h"); units.setTextColor(Color.rgb(90,190,255)); units.setTextSize(16); units.setGravity(Gravity.CENTER); root.addView(units);
        detailView = new TextView(this); detailView.setText("Waiting for GPS…"); detailView.setTextColor(Color.LTGRAY); detailView.setTextSize(13); detailView.setGravity(Gravity.CENTER); detailView.setPadding(8,6,8,8); root.addView(detailView);
        map = new WebView(this); map.setBackgroundColor(Color.rgb(30,42,55)); map.getSettings().setJavaScriptEnabled(true); map.setWebChromeClient(new WebChromeClient()); map.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String u){mapReady=true;}}); map.addJavascriptInterface(this,"Android"); map.loadUrl("file:///android_asset/map.html");
        root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        tripView = new TextView(this); tripView.setText("Trip: 0.00 km   •   Max: 0 km/h"); tripView.setTextColor(Color.WHITE); tripView.setGravity(Gravity.CENTER); tripView.setPadding(4,10,4,10); root.addView(tripView);
        LinearLayout buttons = new LinearLayout(this); buttons.setPadding(10,4,10,14); buttons.setGravity(Gravity.CENTER); buttons.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = button("START TRIP"); recordButton.setOnClickListener(v -> toggleTrip()); buttons.addView(recordButton,new LinearLayout.LayoutParams(0,54,1));
        Button stop = button("STOP"); stop.setOnClickListener(v -> confirmStop()); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,54,1); sp.leftMargin=6; buttons.addView(stop,sp);
        Button history = button("HISTORY"); history.setOnClickListener(v -> showHistory()); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0,54,1); hp.leftMargin=6; buttons.addView(history,hp); root.addView(buttons);
        setContentView(root);
    }
    private Button button(String label){ Button b=new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24,116,210))); return b; }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){ super.onRequestPermissionsResult(requestCode,permissions,results); if(requestCode==REQUEST_LOCATION && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) startGps(); else detailView.setText("Location permission is needed for GPS speed and route."); }
    private void startGps(){ try { if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return; locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); detailView.setText("GPS on • Waiting for a satellite fix…"); } catch(Exception e){detailView.setText("Turn on Location in phone settings.");} }
    @Override public void onLocationChanged(Location loc){
        float kmh=loc.hasSpeed()?loc.getSpeed()*3.6f:0; speedView.setText(String.format(Locale.getDefault(),"%.0f",kmh));
        boolean gps=locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        detailView.setText((gps?"GPS active":"GPS off")+" • Accuracy ±"+String.format(Locale.getDefault(),"%.0f",loc.getAccuracy())+" m");
        if(recording && !paused){ if(!points.isEmpty())distanceMeters+=points.get(points.size()-1).distanceTo(loc); points.add(loc); maxKmh=Math.max(maxKmh,kmh); }
        updateTrip(); if(mapReady){String js="javascript:setPoint("+loc.getLatitude()+","+loc.getLongitude()+","+loc.getAccuracy()+")"; map.evaluateJavascript(js,null);}
    }
    private void toggleTrip(){
        if(!recording){recording=true;paused=false;startedAt=System.currentTimeMillis();distanceMeters=0;maxKmh=0;points.clear();recordButton.setText("PAUSE TRIP");if(mapReady)map.evaluateJavascript("javascript:clearRoute()",null);}
        else if(!paused){paused=true;recordButton.setText("RESUME TRIP");}
        else {paused=false;recordButton.setText("PAUSE TRIP");}
        if(!recording)recordButton.setText("START TRIP"); updateTrip();
    }
    private void updateTrip(){tripView.setText(String.format(Locale.getDefault(),"Trip: %.2f km   •   Max: %.0f km/h%s",distanceMeters/1000,maxKmh,paused?"   •   PAUSED":""));}
    private void confirmStop(){if(!recording)return;new AlertDialog.Builder(this).setTitle("Stop and save trip?").setMessage("Save this trip in your history?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}
    private void showHistory(){
        String raw=getPreferences(0).getString("history","[]"); JSONArray arr;
        try{arr=new JSONArray(raw);}catch(Exception e){arr=new JSONArray();}
        StringBuilder s=new StringBuilder(); for(int i=arr.length()-1;i>=0;i--){JSONObject o=arr.optJSONObject(i);if(o!=null)s.append(o.optString("date")).append("\nDistance: ").append(o.optString("distance")).append(" km • Max: ").append(o.optString("max")).append(" km/h\n\n");}
        if(s.length()==0)s.append("No saved trips yet. Start a trip and tap STOP TRIP to save it.");
        new AlertDialog.Builder(this).setTitle("Trip history").setMessage(s.toString()).setPositiveButton("Close",null).show();
    }
    @Override public void onProviderEnabled(String p){detailView.setText("GPS active • Waiting for location…");}
    @Override public void onProviderDisabled(String p){detailView.setText("GPS is off • Turn on Location in settings.");}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    @Override protected void onDestroy(){super.onDestroy();try{locationManager.removeUpdates(this);}catch(Exception ignored){}}
    @Override public void onBackPressed(){ if(recording){new AlertDialog.Builder(this).setTitle("Stop trip?").setMessage("Save this trip before closing?").setNegativeButton("Keep recording",(d,w)->{}).setPositiveButton("Stop and save",(d,w)->saveTrip()).show();}else super.onBackPressed(); }
    private void saveTrip(){try{JSONArray a=new JSONArray(getPreferences(0).getString("history","[]"));JSONObject o=new JSONObject();o.put("date",new SimpleDateFormat("MMM d, yyyy HH:mm",Locale.getDefault()).format(new Date()));o.put("distance",String.format(Locale.US,"%.2f",distanceMeters/1000));o.put("max",String.format(Locale.US,"%.0f",maxKmh));a.put(o);getPreferences(0).edit().putString("history",a.toString()).apply();}catch(Exception ignored){} recording=false;paused=false;recordButton.setText("START TRIP");updateTrip(); }
    @JavascriptInterface public void stopTrip(){runOnUiThread(()->{if(recording){new AlertDialog.Builder(this).setTitle("Stop and save trip?").setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->saveTrip()).show();}});}
}
