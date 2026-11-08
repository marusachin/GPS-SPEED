package com.example.gpsspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class TripHistoryActivity extends Activity {
    private JSONArray trips = new JSONArray();
    private JSONObject selectedTrip;
    private LinearLayout page;
    private WebView detailMap;
    private static final int NAVY = Color.rgb(10,18,32);

    @Override public void onCreate(Bundle saved){super.onCreate(saved);loadTrips();showList();}

    private void loadTrips(){try{trips=new JSONArray(getSharedPreferences("gps_trips",0).getString("history","[]"));}catch(Exception ignored){trips=new JSONArray();}}

    private void showList(){
        selectedTrip=null;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(NAVY);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(list);
        TextView heading=new TextView(this);heading.setText("TRIP HISTORY");heading.setTextColor(Color.WHITE);heading.setTextSize(22);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(0,0,0,dp(14));list.addView(heading);
        if(trips.length()==0){TextView empty=new TextView(this);empty.setText("No trips saved yet. Record a trip and tap STOP to save its route and altitude.");empty.setTextColor(Color.LTGRAY);empty.setTextSize(16);list.addView(empty);}
        for(int i=trips.length()-1;i>=0;i--){final JSONObject trip=trips.optJSONObject(i);if(trip==null)continue;Button item=new Button(this);double km=trip.optDouble("distanceMeters",-1)/1000.0;if(km<0)km=number(trip,"distance",0);double avg=number(trip,"averageKmh",number(trip,"avg",0));long duration=trip.optLong("durationMillis",0);item.setText(String.format(Locale.getDefault(),"%s\n%.2f km   •   Avg %.0f km/h   •   %s",trip.optString("date","Trip"),km,avg,formatDuration(duration)));item.setTextColor(Color.WHITE);item.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);item.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30,45,64)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(76));p.bottomMargin=dp(8);list.addView(item,p);item.setOnClickListener(v->showDetail(trip));}
        setContentView(scroll);
    }

    private void showDetail(JSONObject trip){
        selectedTrip=trip;page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(NAVY);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(2),dp(8),dp(2));
        Button back=new Button(this);back.setText("‹  HISTORY");back.setTextSize(13);back.setOnClickListener(v->showList());bar.addView(back,new LinearLayout.LayoutParams(dp(118),dp(48)));
        TextView title=new TextView(this);title.setText(trip.optString("date","Trip details"));title.setTextColor(Color.WHITE);title.setTextSize(14);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));page.addView(bar);
        detailMap=new WebView(this);detailMap.getSettings().setJavaScriptEnabled(true);detailMap.getSettings().setUserAgentString(detailMap.getSettings().getUserAgentString()+" GPS-Speedometer/2.0");detailMap.setWebChromeClient(new WebChromeClient());detailMap.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){view.evaluateJavascript("renderTrip("+trip.toString()+")",null);}});page.addView(detailMap,new LinearLayout.LayoutParams(-1,0,1));detailMap.loadUrl("file:///android_asset/trip_detail.html");
        setContentView(page);
    }

    private double number(JSONObject o,String key,double fallback){Object value=o.opt(key);if(value instanceof Number)return ((Number)value).doubleValue();try{return Double.parseDouble(String.valueOf(value));}catch(Exception e){return fallback;}}
    private String formatDuration(long millis){long sec=Math.max(0,millis/1000),h=sec/3600,m=(sec%3600)/60,s=sec%60;return h>0?String.format(Locale.getDefault(),"%02d:%02d:%02d",h,m,s):String.format(Locale.getDefault(),"%02d:%02d",m,s);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onBackPressed(){if(selectedTrip!=null)showList();else super.onBackPressed();}
}

