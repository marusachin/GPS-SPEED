package com.example.gpsspeedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class SatelliteActivity extends Activity {
    private static final int NAVY=Color.rgb(10,18,32);
    private LocationManager locationManager;
    private LinearLayout rows;
    private TextView summary,signalSummary;
    private final ArrayList<Sat> satellites=new ArrayList<>();
    private final GnssStatus.Callback callback=new GnssStatus.Callback(){
        @Override public void onSatelliteStatusChanged(GnssStatus status){
            satellites.clear();int used=0,strong=0,fair=0,weak=0;float sum=0;
            for(int i=0;i<status.getSatelliteCount();i++){
                float cn0=status.getCn0DbHz(i);boolean inFix=status.usedInFix(i);if(inFix)used++;
                if(cn0>=30)strong++;else if(cn0>=20)fair++;else weak++;sum+=cn0;
                satellites.add(new Sat(name(status.getConstellationType()),status.getSvid(),cn0,inFix,status.getElevationDegrees(i),status.getAzimuthDegrees(i)));
            }
            final int visible=status.getSatelliteCount(),fix=used,s=strong,f=fair,w=weak;final float avg=visible>0?sum/visible:0;
            runOnUiThread(()->render(visible,fix,s,f,w,avg));
        }
    };

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);locationManager=(LocationManager)getSystemService(Context.LOCATION_SERVICE);buildPage();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)register();else renderEmpty("Location permission is needed to read satellite data.");}

    private void buildPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(NAVY);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(20));scroll.addView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);Button back=new Button(this);back.setText("‹  BACK");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(105),dp(48)));TextView title=new TextView(this);title.setText("SATELLITE INFORMATION");title.setTextColor(Color.WHITE);title.setTextSize(16);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bar);
        TextView note=new TextView(this);note.setText("Live GNSS satellites received by your phone");note.setTextColor(Color.LTGRAY);note.setTextSize(13);note.setPadding(0,dp(8),0,dp(12));root.addView(note);
        summary=new TextView(this);summary.setTextColor(Color.WHITE);summary.setTextSize(18);summary.setTypeface(null,android.graphics.Typeface.BOLD);summary.setPadding(0,dp(8),0,dp(4));root.addView(summary);
        signalSummary=new TextView(this);signalSummary.setTextColor(Color.rgb(150,200,235));signalSummary.setTextSize(14);signalSummary.setPadding(0,0,0,dp(12));root.addView(signalSummary);
        TextView legend=new TextView(this);legend.setText("Signal strength   •   Strong ≥30   Fair ≥20   Weak <20 dB-Hz");legend.setTextColor(Color.LTGRAY);legend.setTextSize(12);legend.setPadding(0,dp(10),0,dp(10));root.addView(legend);
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);root.addView(rows);
        setContentView(scroll);
    }

    private void register(){try{if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){renderEmpty("Location is off. Turn on GPS, then return here.");return;}if(Build.VERSION.SDK_INT>=24)locationManager.registerGnssStatusCallback(callback,new Handler(Looper.getMainLooper()));else renderEmpty("Satellite details require Android 7 or newer.");}catch(SecurityException e){renderEmpty("Location permission is needed to read satellite data.");}catch(Exception e){renderEmpty("Unable to read satellite status. Check that GPS is on.");}}
    private void renderEmpty(String message){if(summary!=null){summary.setText(message);signalSummary.setText("");rows.removeAllViews();}}

    private void render(int visible,int used,int strong,int fair,int weak,float avg){
        summary.setText("Visible: "+visible+"     Used for GPS fix: "+used);
        signalSummary.setText(String.format(Locale.getDefault(),"Strong %d   •   Fair %d   •   Weak %d   •   Average %.0f dB-Hz",strong,fair,weak,avg));
        Collections.sort(satellites,Comparator.comparingDouble((Sat s)->s.cn0).reversed());rows.removeAllViews();
        if(satellites.isEmpty()){TextView empty=new TextView(this);empty.setText("Searching for satellites… Try outdoors with a clear view of the sky.");empty.setTextColor(Color.LTGRAY);empty.setTextSize(15);empty.setPadding(0,dp(15),0,dp(10));rows.addView(empty);return;}
        for(Sat sat:satellites){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(10),dp(10),dp(10),dp(8));row.setBackgroundColor(Color.rgb(24,35,51));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(86));rp.bottomMargin=dp(7);rows.addView(row,rp);
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView id=new TextView(this);id.setText(sat.system+"  •  SVID "+sat.id);id.setTextColor(Color.WHITE);id.setTextSize(15);id.setTypeface(null,android.graphics.Typeface.BOLD);top.addView(id,new LinearLayout.LayoutParams(0,dp(24),1));TextView strength=new TextView(this);strength.setText(String.format(Locale.getDefault(),"%.0f dB-Hz%s",sat.cn0,sat.used?"  •  IN USE":""));strength.setTextColor(sat.cn0>=30?Color.rgb(90,220,140):sat.cn0>=20?Color.rgb(255,205,90):Color.rgb(255,145,120));strength.setTextSize(12);top.addView(strength);row.addView(top);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(50);bar.setProgress(Math.min(50,Math.round(sat.cn0)));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(6));bp.topMargin=dp(4);row.addView(bar,bp);
            TextView angles=new TextView(this);angles.setText(String.format(Locale.getDefault(),"Elevation %.0f°   •   Azimuth %.0f°",sat.elevation,sat.azimuth));angles.setTextColor(Color.LTGRAY);angles.setTextSize(12);angles.setPadding(0,dp(5),0,0);row.addView(angles);
        }
    }

    private String name(int c){switch(c){case GnssStatus.CONSTELLATION_GPS:return "GPS";case GnssStatus.CONSTELLATION_GLONASS:return "GLONASS";case GnssStatus.CONSTELLATION_GALILEO:return "Galileo";case GnssStatus.CONSTELLATION_BEIDOU:return "BeiDou";case GnssStatus.CONSTELLATION_QZSS:return "QZSS";case GnssStatus.CONSTELLATION_SBAS:return "SBAS";case GnssStatus.CONSTELLATION_IRNSS:return "NavIC";default:return "GNSS";}}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onDestroy(){super.onDestroy();try{if(Build.VERSION.SDK_INT>=24)locationManager.unregisterGnssStatusCallback(callback);}catch(Exception ignored){}}
    private static class Sat{final String system;final int id;final float cn0, elevation,azimuth;final boolean used;Sat(String n,int i,float c,boolean u,float e,float a){system=n;id=i;cn0=c;used=u;elevation=e;azimuth=a;}}
}

