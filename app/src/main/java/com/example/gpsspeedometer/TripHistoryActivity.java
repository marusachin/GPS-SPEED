package com.example.gpsspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.location.Geocoder;
import android.location.Address;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
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
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.xmlpull.v1.XmlSerializer;
import android.util.Xml;

public class TripHistoryActivity extends Activity {
    private JSONArray trips = new JSONArray();
    private JSONObject selectedTrip;
    private int selectedTripIndex=-1;
    private static final int REQUEST_EXPORT_GPX=40;
    private static final int REQUEST_PICK_BACKUP_FOLDER=41;
    private static final int REQUEST_IMPORT_BACKUP=42;
    private String pendingFolderAction=null;
    private JSONArray pendingImportedTrips;
    private static final String GPX_NAMESPACE="http://www.topografix.com/GPX/1/1";
    private LinearLayout page;
    private WebView detailMap;
    private static final int NAVY = Color.rgb(10,18,32);
    private final ExecutorService geocoderWorker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);loadTrips();showList();}

    private void loadTrips(){try{trips=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));}catch(Exception ignored){trips=new JSONArray();}}

    private void showList(){
        selectedTrip=null;selectedTripIndex=-1;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(NAVY);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(list);
        addTabs(list,false);
        TextView heading=new TextView(this);heading.setText("TRIP HISTORY");heading.setTextColor(Color.WHITE);heading.setTextSize(22);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(0,0,0,dp(14));list.addView(heading);
        if(trips.length()==0){TextView empty=new TextView(this);empty.setText("No trips saved yet. Record a trip and tap STOP to save its route and altitude.");empty.setTextColor(Color.LTGRAY);empty.setTextSize(16);list.addView(empty);}
        for(int i=trips.length()-1;i>=0;i--){final JSONObject trip=trips.optJSONObject(i);if(trip==null)continue;Button item=new Button(this);double km=trip.optDouble("distanceMeters",-1)/1000.0;if(km<0)km=number(trip,"distance",0);double avg=number(trip,"averageKmh",number(trip,"avg",0));long duration=trip.optLong("durationMillis",0);item.setText(String.format(Locale.getDefault(),"%s\n%.2f km   •   Avg %.0f km/h   •   %s",trip.optString("date","Trip"),km,avg,formatDuration(duration)));item.setTextColor(Color.WHITE);item.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);item.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30,45,64)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(76));p.bottomMargin=dp(8);list.addView(item,p);final int tripIndex=i;item.setOnClickListener(v->showDetail(trip,tripIndex));}
        applySafeInsets(scroll);setContentView(scroll);
    }

    private void addTabs(LinearLayout parent,boolean toolsSelected){
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.setPadding(0,0,0,dp(10));
        Button tripsTab=smallButton("TRIPS");tripsTab.setTextSize(13);tripsTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(toolsSelected?Color.rgb(35,47,64):Color.rgb(24,116,210)));tripsTab.setTextColor(Color.WHITE);tripsTab.setOnClickListener(v->showList());tabs.addView(tripsTab,new LinearLayout.LayoutParams(0,dp(46),1));
        Button toolsTab=smallButton("IMPORT / EXPORT");toolsTab.setTextSize(13);toolsTab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(toolsSelected?Color.rgb(24,116,210):Color.rgb(35,47,64)));toolsTab.setTextColor(Color.WHITE);toolsTab.setOnClickListener(v->showImportExport());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.leftMargin=dp(8);tabs.addView(toolsTab,p);parent.addView(tabs);
    }
    private void showImportExport(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(NAVY);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(content);addTabs(content,true);
        TextView heading=new TextView(this);heading.setText("IMPORT / EXPORT");heading.setTextColor(Color.WHITE);heading.setTextSize(22);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(0,dp(6),0,dp(12));content.addView(heading);
        TextView note=new TextView(this);note.setText("Choose a folder once. New trips are copied there automatically; you can also back up all trips and save GPX routes there.");note.setTextColor(Color.LTGRAY);note.setTextSize(14);note.setPadding(0,0,0,dp(12));content.addView(note);
        TextView folder=new TextView(this);folder.setText("Saved folder: "+backupFolderLabel());folder.setTextColor(Color.WHITE);folder.setTextSize(15);folder.setPadding(0,0,0,dp(8));content.addView(folder);
        Button choose=actionButton("CHOOSE SAVE FOLDER");choose.setOnClickListener(v->chooseBackupFolder(null));content.addView(choose,fullWidth());
        Button backup=actionButton("BACK UP ALL TRIPS");backup.setOnClickListener(v->beginHistoryBackup());LinearLayout.LayoutParams bp=fullWidth();bp.topMargin=dp(8);content.addView(backup,bp);
        Button restore=actionButton("RESTORE A BACKUP");restore.setOnClickListener(v->beginRestoreBackup());LinearLayout.LayoutParams rp=fullWidth();rp.topMargin=dp(8);content.addView(restore,rp);
        Button sample=actionButton("SAVE SAMPLE TRIP FILE");sample.setOnClickListener(v->copySampleBackup());LinearLayout.LayoutParams sampleParams=fullWidth();sampleParams.topMargin=dp(8);content.addView(sample,sampleParams);
        TextView details=new TextView(this);details.setText("Backups include trip dates, route points, speed, elevation, and report details. Restoring merges trips and keeps recordings already on this phone.");details.setTextColor(Color.LTGRAY);details.setTextSize(13);details.setPadding(0,dp(14),0,0);content.addView(details);
        applySafeInsets(scroll);setContentView(scroll);
    }
    private Button actionButton(String label){Button b=smallButton(label);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24,116,210)));return b;}
    private LinearLayout.LayoutParams fullWidth(){return new LinearLayout.LayoutParams(-1,dp(48));}
    private android.net.Uri backupFolder(){String value=getSharedPreferences("gps_backup",0).getString("folder",null);try{return value==null?null:Uri.parse(value);}catch(Exception e){return null;}}
    private String backupFolderLabel(){Uri tree=backupFolder();if(tree==null)return "Not chosen";try{Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));try(Cursor cursor=getContentResolver().query(doc,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())return cursor.getString(0);}}catch(Exception ignored){}return "Chosen folder";}
    private void chooseBackupFolder(String after){pendingFolderAction=after;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(intent,REQUEST_PICK_BACKUP_FOLDER);}catch(Exception e){Toast.makeText(this,"Could not open folder picker",Toast.LENGTH_LONG).show();}}
    private void beginHistoryBackup(){if(trips.length()==0){Toast.makeText(this,"There are no trips to back up yet",Toast.LENGTH_SHORT).show();return;}if(backupFolder()==null){chooseBackupFolder("backup");return;}writeHistoryBackup();}
    private Uri createInBackupFolder(String fileName,String mimeType)throws Exception{Uri tree=backupFolder();if(tree==null)throw new IllegalStateException("Choose a folder first");Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));Uri file=DocumentsContract.createDocument(getContentResolver(),parent,mimeType,fileName);if(file==null)throw new java.io.IOException("Could not create file");return file;}
    private String backupFileName(){return "GPS-Speedometer-backup-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".json";}
    private void writeHistoryBackup(){try{Uri file=createInBackupFolder(backupFileName(),"application/json");try(OutputStream out=getContentResolver().openOutputStream(file)){if(out==null)throw new java.io.IOException("Could not open backup file");out.write(trips.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();}Toast.makeText(this,"Backup saved in "+backupFolderLabel(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Backup failed. Choose the save folder again.",Toast.LENGTH_LONG).show();}}
    private void copySampleBackup(){if(backupFolder()==null){chooseBackupFolder("sample");return;}writeSampleBackup();}
    private void writeSampleBackup(){try{Uri file=createInBackupFolder("Sample-Manpada-to-Borivali.json","application/json");try(InputStream in=getAssets().open("sample-trip-backup.json");OutputStream out=getContentResolver().openOutputStream(file)){if(out==null)throw new java.io.IOException("Could not open sample file");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.flush();}Toast.makeText(this,"Sample trip file saved in "+backupFolderLabel()+". Use Restore a backup to import it.",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Could not copy the sample file to the chosen folder",Toast.LENGTH_LONG).show();}}
    private void beginRestoreBackup(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);try{startActivityForResult(intent,REQUEST_IMPORT_BACKUP);}catch(Exception e){Toast.makeText(this,"Could not open file picker",Toast.LENGTH_LONG).show();}}
    private void readHistoryBackup(Uri uri){try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){if(in==null)throw new java.io.IOException("No file");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);String text=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);JSONArray incoming; if(text.trim().startsWith("{"))incoming=new JSONObject(text).optJSONArray("trips");else incoming=new JSONArray(text);if(incoming==null)throw new IllegalArgumentException("Backup does not contain trips");pendingImportedTrips=incoming;new AlertDialog.Builder(this).setTitle("Restore trip backup?").setMessage("Merge "+incoming.length()+" trips into this phone's history. Existing trips will be kept.").setNegativeButton("Cancel",(d,w)->pendingImportedTrips=null).setPositiveButton("Merge trips",(d,w)->mergeHistoryBackup()).show();}catch(Exception e){Toast.makeText(this,"Could not read this backup file",Toast.LENGTH_LONG).show();}}
    private String tripKey(JSONObject trip){long started=trip.optLong("startTime",0);if(started>0)return "t:"+started;JSONArray pts=trip.optJSONArray("points");JSONObject first=pts==null?null:pts.optJSONObject(0);return "f:"+trip.optString("date","")+"|"+trip.optString("distanceMeters","")+"|"+trip.optString("durationMillis","")+"|"+(first==null?"":first.optString("lat","")+","+first.optString("lon",""));}
    private void mergeHistoryBackup(){if(pendingImportedTrips==null)return;JSONArray merged=new JSONArray();Set<String> keys=new HashSet<>();for(int i=0;i<trips.length();i++){JSONObject t=trips.optJSONObject(i);if(t!=null){String k=tripKey(t);if(keys.add(k))merged.put(t);}}int added=0;for(int i=0;i<pendingImportedTrips.length();i++){JSONObject t=pendingImportedTrips.optJSONObject(i);if(t!=null&&keys.add(tripKey(t))){merged.put(t);added++;}}pendingImportedTrips=null;trips=merged;getSharedPreferences("gps_trips",0).edit().putString("history",trips.toString()).apply();Toast.makeText(this,"Restored "+added+" trips; existing trips kept",Toast.LENGTH_LONG).show();showList();}
    private void showDetail(JSONObject trip,int index){
        selectedTrip=trip;selectedTripIndex=index;page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(NAVY);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(2),dp(8),dp(2));
        Button back=new Button(this);back.setText("‹ HISTORY");back.setTextSize(11);back.setTextColor(Color.WHITE);back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35,47,64)));back.setOnClickListener(v->showList());bar.addView(back,new LinearLayout.LayoutParams(dp(100),dp(46)));
        TextView title=new TextView(this);title.setText(trip.optString("date","Trip details"));title.setTextColor(Color.WHITE);title.setTextSize(12);title.setSingleLine(true);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));Button export=smallButton("EXPORT");export.setTextColor(Color.WHITE);export.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(24,116,210)));export.setOnClickListener(v->beginExport());bar.addView(export,new LinearLayout.LayoutParams(dp(68),dp(44)));Button delete=smallButton("DELETE");delete.setTextSize(11);delete.setTextColor(Color.WHITE);delete.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(190,45,55)));delete.setOnClickListener(v->confirmDelete());bar.addView(delete,new LinearLayout.LayoutParams(dp(82),dp(44)));page.addView(bar);
        detailMap=new WebView(this);detailMap.getSettings().setJavaScriptEnabled(true);detailMap.getSettings().setDomStorageEnabled(true);detailMap.getSettings().setUserAgentString(detailMap.getSettings().getUserAgentString()+" GPS-Speedometer/2.0");detailMap.setWebChromeClient(new WebChromeClient());detailMap.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){view.evaluateJavascript("renderTrip("+trip.toString()+")",null);JSONArray route=trip.optJSONArray("points");if(route!=null&&route.length()>0){lookupEndpointName(trip,0,"start");lookupEndpointName(trip,route.length()-1,"stop");}}});page.addView(detailMap,new LinearLayout.LayoutParams(-1,0,1));detailMap.loadUrl("file:///android_asset/trip_detail.html");
        applySafeInsets(page);setContentView(page);
    }

    private void lookupEndpointName(JSONObject trip,int pointIndex,String elementId){
        JSONArray points=trip.optJSONArray("points");JSONObject point=points==null?null:points.optJSONObject(pointIndex);if(point==null)return;
        double lat=point.optDouble("lat",Double.NaN),lon=point.optDouble("lon",Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))return;
        geocoderWorker.execute(()->{
            String name=null;
            try{if(Geocoder.isPresent()){List<Address> results=new Geocoder(getApplicationContext(),Locale.getDefault()).getFromLocation(lat,lon,1);if(results!=null&&!results.isEmpty()){Address address=results.get(0);name=address.getAddressLine(0);if(name==null||name.trim().isEmpty())name=address.getSubLocality();if(name==null||name.trim().isEmpty())name=address.getLocality();}}}catch(Exception ignored){}
            if(name==null||name.trim().isEmpty()){try{Thread.sleep(1100);name=reverseNameFromOsm(lat,lon);}catch(Exception ignored){}}
            if(name!=null&&!name.trim().isEmpty()){final String safeName=name.trim();runOnUiThread(()->{if(selectedTrip==trip&&detailMap!=null)detailMap.evaluateJavascript("setPointName("+JSONObject.quote(elementId)+","+JSONObject.quote(safeName)+")",null);});}
        });
    }
    private String reverseNameFromOsm(double lat,double lon){HttpURLConnection connection=null;try{String address=String.format(Locale.US,"https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f",lat,lon);connection=(HttpURLConnection)new java.net.URL(address).openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setRequestProperty("User-Agent","GPS-Speedometer/2.18 (Android trip report)");if(connection.getResponseCode()!=200)return null;StringBuilder body=new StringBuilder();try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)body.append(line);}JSONObject root=new JSONObject(body.toString()),a=root.optJSONObject("address");if(a!=null){String[] keys={"road","pedestrian","neighbourhood","suburb","city_district","town","city","village","state_district"};java.util.LinkedHashSet<String> parts=new java.util.LinkedHashSet<>();for(String key:keys){String value=a.optString(key,"").trim();if(!value.isEmpty())parts.add(value);if(parts.size()>=3)break;}if(!parts.isEmpty())return android.text.TextUtils.join(", ",parts);}return root.optString("display_name",null);}catch(Exception e){return null;}finally{if(connection!=null)connection.disconnect();}}

    private void applySafeInsets(View root){root.setOnApplyWindowInsetsListener((view,insets)->{int top=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}view.setPadding(view.getPaddingLeft(),top,view.getPaddingRight(),bottom);return insets;});}
    private Button smallButton(String label){Button b=new Button(this);b.setText(label);b.setTextSize(10);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(4),0,dp(4),0);return b;}
    private void confirmDelete(){if(selectedTripIndex<0||selectedTripIndex>=trips.length())return;new AlertDialog.Builder(this).setTitle("Delete this trip?").setMessage("This removes the trip and its saved route from history.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->deleteSelectedTrip()).show();}
    private void deleteSelectedTrip(){JSONArray remaining=new JSONArray();for(int i=0;i<trips.length();i++)if(i!=selectedTripIndex){Object item=trips.opt(i);if(item!=null)remaining.put(item);}trips=remaining;getSharedPreferences("gps_trips",0).edit().putString("history",trips.toString()).apply();Toast.makeText(this,"Trip deleted",Toast.LENGTH_SHORT).show();showList();}
    private void beginExport(){if(selectedTrip==null)return;JSONArray route=selectedTrip.optJSONArray("points");if(route==null||route.length()==0){Toast.makeText(this,"This trip has no saved route to export",Toast.LENGTH_LONG).show();return;}if(backupFolder()==null){chooseBackupFolder("gpx");return;}saveGpxInBackupFolder();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(requestCode==REQUEST_PICK_BACKUP_FOLDER){try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));getSharedPreferences("gps_backup",0).edit().putString("folder",uri.toString()).apply();String action=pendingFolderAction;pendingFolderAction=null;if("backup".equals(action))writeHistoryBackup();else if("gpx".equals(action))saveGpxInBackupFolder();else if("sample".equals(action))writeSampleBackup();else showImportExport();}catch(Exception e){Toast.makeText(this,"Could not save folder access. Choose another folder.",Toast.LENGTH_LONG).show();}}else if(requestCode==REQUEST_IMPORT_BACKUP){try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}readHistoryBackup(uri);}else if(requestCode==REQUEST_EXPORT_GPX){writeGpx(uri);}}
    private void saveGpxInBackupFolder(){try{String date=selectedTrip.optString("date","GPS_trip").replaceAll("[^A-Za-z0-9._-]+","_");Uri file=createInBackupFolder(date+".gpx","application/gpx+xml");writeGpx(file);Toast.makeText(this,"GPX saved in "+backupFolderLabel(),Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Could not save GPX. Choose the folder again.",Toast.LENGTH_LONG).show();}}
    private void writeGpx(Uri uri){try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new java.io.IOException("Unable to open output file");JSONArray route=selectedTrip.optJSONArray("points");XmlSerializer xml=Xml.newSerializer();xml.setOutput(output,"UTF-8");xml.startDocument("UTF-8",true);xml.setPrefix("",GPX_NAMESPACE);xml.startTag(GPX_NAMESPACE, "gpx");xml.attribute("", "version","1.1");xml.attribute("", "creator","GPS Speedometer");xml.startTag(GPX_NAMESPACE, "metadata");xml.startTag(GPX_NAMESPACE, "name");xml.text(selectedTrip.optString("date","GPS trip"));xml.endTag(GPX_NAMESPACE, "name");xml.endTag(GPX_NAMESPACE, "metadata");xml.startTag(GPX_NAMESPACE, "trk");xml.startTag(GPX_NAMESPACE, "name");xml.text(selectedTrip.optString("date","GPS trip"));xml.endTag(GPX_NAMESPACE, "name");xml.startTag(GPX_NAMESPACE, "trkseg");SimpleDateFormat time=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);time.setTimeZone(TimeZone.getTimeZone("UTC"));for(int i=0;i<route.length();i++){JSONObject point=route.optJSONObject(i);if(point==null)continue;double lat=point.optDouble("lat",Double.NaN),lon=point.optDouble("lon",Double.NaN);if(Double.isNaN(lat)||Double.isNaN(lon))continue;xml.startTag(GPX_NAMESPACE, "trkpt");xml.attribute("", "lat",String.format(Locale.US,"%.7f",lat));xml.attribute("", "lon",String.format(Locale.US,"%.7f",lon));double elevation=point.optDouble("alt",Double.NaN);if(!Double.isNaN(elevation)&&!Double.isInfinite(elevation)){xml.startTag(GPX_NAMESPACE, "ele");xml.text(String.format(Locale.US,"%.1f",elevation));xml.endTag(GPX_NAMESPACE, "ele");}long timestamp=point.optLong("time",0);if(timestamp>0){xml.startTag(GPX_NAMESPACE, "time");xml.text(time.format(new Date(timestamp)));xml.endTag(GPX_NAMESPACE, "time");}xml.endTag(GPX_NAMESPACE, "trkpt");}xml.endTag(GPX_NAMESPACE, "trkseg");xml.endTag(GPX_NAMESPACE, "trk");xml.endTag(GPX_NAMESPACE, "gpx");xml.endDocument();xml.flush();output.flush();Toast.makeText(this,"GPX route exported",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Could not export this trip",Toast.LENGTH_LONG).show();}}
    private double number(JSONObject o,String key,double fallback){Object value=o.opt(key);if(value instanceof Number)return ((Number)value).doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception e){return fallback;}}
    private String formatDuration(long millis){long sec=Math.max(0,millis/1000),h=sec/3600,m=(sec%3600)/60,s=sec%60;return h>0?String.format(Locale.getDefault(),"%02d:%02d:%02d",h,m,s):String.format(Locale.getDefault(),"%02d:%02d",m,s);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onBackPressed(){if(selectedTrip!=null)showList();else super.onBackPressed();}
    @Override protected void onDestroy(){geocoderWorker.shutdownNow();super.onDestroy();}
}

