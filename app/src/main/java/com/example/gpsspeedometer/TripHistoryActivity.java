package com.example.gpsspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.location.Geocoder;
import android.location.Address;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.io.OutputStream;
import org.xmlpull.v1.XmlSerializer;
import android.util.Xml;

public class TripHistoryActivity extends Activity {
    private JSONArray trips = new JSONArray();
    private JSONObject selectedTrip;
    private int selectedTripIndex=-1;
    private static final int REQUEST_EXPORT_GPX=40;
    private static final String GPX_NAMESPACE="http://www.topografix.com/GPX/1/1";
    private LinearLayout page;
    private WebView detailMap;
    private static final int NAVY = Color.rgb(10,18,32);
    private final ExecutorService geocoderWorker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle saved){super.onCreate(saved);loadTrips();showList();}

    private void loadTrips(){try{trips=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));}catch(Exception ignored){trips=new JSONArray();}}

    private void showList(){
        selectedTrip=null;selectedTripIndex=-1;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(NAVY);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(list);
        TextView heading=new TextView(this);heading.setText("TRIP HISTORY");heading.setTextColor(Color.WHITE);heading.setTextSize(22);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(0,0,0,dp(14));list.addView(heading);
        if(trips.length()==0){TextView empty=new TextView(this);empty.setText("No trips saved yet. Record a trip and tap STOP to save its route and altitude.");empty.setTextColor(Color.LTGRAY);empty.setTextSize(16);list.addView(empty);}
        for(int i=trips.length()-1;i>=0;i--){final JSONObject trip=trips.optJSONObject(i);if(trip==null)continue;Button item=new Button(this);double km=trip.optDouble("distanceMeters",-1)/1000.0;if(km<0)km=number(trip,"distance",0);double avg=number(trip,"averageKmh",number(trip,"avg",0));long duration=trip.optLong("durationMillis",0);item.setText(String.format(Locale.getDefault(),"%s\n%.2f km   •   Avg %.0f km/h   •   %s",trip.optString("date","Trip"),km,avg,formatDuration(duration)));item.setTextColor(Color.WHITE);item.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);item.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30,45,64)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(76));p.bottomMargin=dp(8);list.addView(item,p);final int tripIndex=i;item.setOnClickListener(v->showDetail(trip,tripIndex));}
        setContentView(scroll);
    }

    private void showDetail(JSONObject trip,int index){
        selectedTrip=trip;selectedTripIndex=index;page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(NAVY);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(2),dp(8),dp(2));
        Button back=new Button(this);back.setText("‹  HISTORY");back.setTextSize(13);back.setOnClickListener(v->showList());bar.addView(back,new LinearLayout.LayoutParams(dp(118),dp(48)));
        TextView title=new TextView(this);title.setText(trip.optString("date","Trip details"));title.setTextColor(Color.WHITE);title.setTextSize(13);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));Button export=smallButton("EXPORT");export.setOnClickListener(v->beginExport());bar.addView(export,new LinearLayout.LayoutParams(dp(72),dp(44)));Button delete=smallButton("DELETE");delete.setTextColor(Color.rgb(255,190,190));delete.setOnClickListener(v->confirmDelete());bar.addView(delete,new LinearLayout.LayoutParams(dp(72),dp(44)));page.addView(bar);
        detailMap=new WebView(this);detailMap.getSettings().setJavaScriptEnabled(true);detailMap.getSettings().setUserAgentString(detailMap.getSettings().getUserAgentString()+" GPS-Speedometer/2.0");detailMap.setWebChromeClient(new WebChromeClient());detailMap.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){view.evaluateJavascript("renderTrip("+trip.toString()+")",null);JSONArray route=trip.optJSONArray("points");if(route!=null&&route.length()>0){lookupEndpointName(trip,0,"start");lookupEndpointName(trip,route.length()-1,"stop");}}});page.addView(detailMap,new LinearLayout.LayoutParams(-1,0,1));detailMap.loadUrl("file:///android_asset/trip_detail.html");
        setContentView(page);
    }

    private void lookupEndpointName(JSONObject trip,int pointIndex,String elementId){
        JSONArray points=trip.optJSONArray("points");JSONObject point=points==null?null:points.optJSONObject(pointIndex);if(point==null)return;
        double lat=point.optDouble("lat",Double.NaN),lon=point.optDouble("lon",Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))return;
        geocoderWorker.execute(()->{
            String name=null;
            try{if(Geocoder.isPresent()){List<Address> results=new Geocoder(getApplicationContext(),Locale.getDefault()).getFromLocation(lat,lon,1);if(results!=null&&!results.isEmpty()){Address address=results.get(0);name=address.getFeatureName();if(name==null||name.trim().isEmpty())name=address.getSubLocality();if(name==null||name.trim().isEmpty())name=address.getLocality();if(name==null||name.trim().isEmpty())name=address.getAddressLine(0);}}}catch(Exception ignored){}
            if(name!=null&&!name.trim().isEmpty()){final String safeName=name.trim();runOnUiThread(()->{if(selectedTrip==trip&&detailMap!=null)detailMap.evaluateJavascript("setPointName("+JSONObject.quote(elementId)+","+JSONObject.quote(safeName)+")",null);});}
        });
    }
    private Button smallButton(String label){Button b=new Button(this);b.setText(label);b.setTextSize(10);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);return b;}
    private void confirmDelete(){if(selectedTripIndex<0||selectedTripIndex>=trips.length())return;new AlertDialog.Builder(this).setTitle("Delete this trip?").setMessage("This removes the trip and its saved route from history.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->deleteSelectedTrip()).show();}
    private void deleteSelectedTrip(){JSONArray remaining=new JSONArray();for(int i=0;i<trips.length();i++)if(i!=selectedTripIndex){Object item=trips.opt(i);if(item!=null)remaining.put(item);}trips=remaining;getSharedPreferences("gps_trips",0).edit().putString("history",trips.toString()).apply();Toast.makeText(this,"Trip deleted",Toast.LENGTH_SHORT).show();showList();}
    private void beginExport(){if(selectedTrip==null)return;JSONArray route=selectedTrip.optJSONArray("points");if(route==null||route.length()==0){Toast.makeText(this,"This trip has no saved route to export",Toast.LENGTH_LONG).show();return;}String date=selectedTrip.optString("date","GPS_trip").replaceAll("[^A-Za-z0-9._-]+","_");Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/gpx+xml");intent.putExtra(Intent.EXTRA_TITLE,date+".gpx");try{startActivityForResult(intent,REQUEST_EXPORT_GPX);}catch(Exception e){Toast.makeText(this,"Could not open the file picker",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_EXPORT_GPX||resultCode!=RESULT_OK||data==null||data.getData()==null)return;writeGpx(data.getData());}
    private void writeGpx(Uri uri){try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new java.io.IOException("Unable to open output file");JSONArray route=selectedTrip.optJSONArray("points");XmlSerializer xml=Xml.newSerializer();xml.setOutput(output,"UTF-8");xml.startDocument("UTF-8",true);xml.setPrefix("",GPX_NAMESPACE);xml.startTag(GPX_NAMESPACE, "gpx");xml.attribute("", "version","1.1");xml.attribute("", "creator","GPS Speedometer");xml.startTag(GPX_NAMESPACE, "metadata");xml.startTag(GPX_NAMESPACE, "name");xml.text(selectedTrip.optString("date","GPS trip"));xml.endTag(GPX_NAMESPACE, "name");xml.endTag(GPX_NAMESPACE, "metadata");xml.startTag(GPX_NAMESPACE, "trk");xml.startTag(GPX_NAMESPACE, "name");xml.text(selectedTrip.optString("date","GPS trip"));xml.endTag(GPX_NAMESPACE, "name");xml.startTag(GPX_NAMESPACE, "trkseg");SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);time.setTimeZone(TimeZone.getTimeZone("UTC"));for(int i=0;i<route.length();i++){JSONObject point=route.optJSONObject(i);if(point==null)continue;double lat=point.optDouble("lat",Double.NaN),lon=point.optDouble("lon",Double.NaN);if(Double.isNaN(lat)||Double.isNaN(lon))continue;xml.startTag(GPX_NAMESPACE, "trkpt");xml.attribute("", "lat",String.format(Locale.US,"%.7f",lat));xml.attribute("", "lon",String.format(Locale.US,"%.7f",lon));double elevation=point.optDouble("alt",Double.NaN);if(!Double.isNaN(elevation)&&!Double.isInfinite(elevation)){xml.startTag(GPX_NAMESPACE, "ele");xml.text(String.format(Locale.US,"%.1f",elevation));xml.endTag(GPX_NAMESPACE, "ele");}long timestamp=point.optLong("time",0);if(timestamp>0){xml.startTag(GPX_NAMESPACE, "time");xml.text(time.format(new Date(timestamp)));xml.endTag(GPX_NAMESPACE, "time");}xml.endTag(GPX_NAMESPACE, "trkpt");}xml.endTag(GPX_NAMESPACE, "trkseg");xml.endTag(GPX_NAMESPACE, "trk");xml.endTag(GPX_NAMESPACE, "gpx");xml.endDocument();xml.flush();output.flush();Toast.makeText(this,"GPX route exported",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Could not export this trip",Toast.LENGTH_LONG).show();}}
    private double number(JSONObject o,String key,double fallback){Object value=o.opt(key);if(value instanceof Number)return ((Number)value).doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception e){return fallback;}}
    private String formatDuration(long millis){long sec=Math.max(0,millis/1000),h=sec/3600,m=(sec%3600)/60,s=sec%60;return h>0?String.format(Locale.getDefault(),"%02d:%02d:%02d",h,m,s):String.format(Locale.getDefault(),"%02d:%02d",m,s);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onBackPressed(){if(selectedTrip!=null)showList();else super.onBackPressed();}
    @Override protected void onDestroy(){geocoderWorker.shutdownNow();super.onDestroy();}
}

