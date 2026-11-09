package com.example.gpsspeedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
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
    private SkyPlotView skyPlot;
    private final ArrayList<Sat> satellites=new ArrayList<>();
    private final GnssStatus.Callback callback=new GnssStatus.Callback(){
        @Override public void onSatelliteStatusChanged(GnssStatus status){
            satellites.clear();int used=0,strong=0,fair=0,weak=0;float sum=0;
            for(int i=0;i<status.getSatelliteCount();i++){
                float cn0=status.getCn0DbHz(i);boolean inFix=status.usedInFix(i);if(inFix)used++;
                if(cn0>=30)strong++;else if(cn0>=20)fair++;else weak++;sum+=cn0;
                satellites.add(new Sat(name(status.getConstellationType(i)),status.getSvid(i),cn0,inFix,status.getElevationDegrees(i),status.getAzimuthDegrees(i)));
            }
            final int visible=status.getSatelliteCount(),fix=used,s=strong,f=fair,w=weak;final float avg=visible>0?sum/visible:0;
            runOnUiThread(()->render(visible,fix,s,f,w,avg));
        }
    };

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);locationManager=(LocationManager)getSystemService(Context.LOCATION_SERVICE);buildPage();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)register();else renderEmpty("Location permission is needed to read satellite data.");}

    private void buildPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(NAVY);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(8),dp(16),dp(20));root.setOnApplyWindowInsetsListener((view,insets)->{int top=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}view.setPadding(dp(16),dp(8)+top,dp(16),dp(20)+bottom);return insets;});scroll.addView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);Button back=new Button(this);back.setText("‹  BACK");back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(105),dp(48)));TextView title=new TextView(this);title.setText("SATELLITE INFORMATION");title.setTextColor(Color.WHITE);title.setTextSize(16);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bar);
        TextView note=new TextView(this);note.setText("Live GNSS satellites received by your phone");note.setTextColor(Color.LTGRAY);note.setTextSize(13);note.setPadding(0,dp(8),0,dp(12));root.addView(note);
        summary=new TextView(this);summary.setTextColor(Color.WHITE);summary.setTextSize(18);summary.setTypeface(null,android.graphics.Typeface.BOLD);summary.setPadding(0,dp(8),0,dp(4));root.addView(summary);
        signalSummary=new TextView(this);signalSummary.setTextColor(Color.rgb(150,200,235));signalSummary.setTextSize(14);signalSummary.setPadding(0,0,0,dp(12));root.addView(signalSummary);
        skyPlot=new SkyPlotView(this);LinearLayout.LayoutParams skyParams=new LinearLayout.LayoutParams(-1,dp(270));skyParams.bottomMargin=dp(10);root.addView(skyPlot,skyParams);
        TextView legend=new TextView(this);legend.setText("Signal strength   •   Strong ≥30   Fair ≥20   Weak <20 dB-Hz");legend.setTextColor(Color.LTGRAY);legend.setTextSize(12);legend.setPadding(0,dp(10),0,dp(10));root.addView(legend);
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);root.addView(rows);
        setContentView(scroll);
    }

    private void register(){try{if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){renderEmpty("Location is off. Turn on GPS, then return here.");return;}if(Build.VERSION.SDK_INT>=24)locationManager.registerGnssStatusCallback(callback,new Handler(Looper.getMainLooper()));else renderEmpty("Satellite details require Android 7 or newer.");}catch(SecurityException e){renderEmpty("Location permission is needed to read satellite data.");}catch(Exception e){renderEmpty("Unable to read satellite status. Check that GPS is on.");}}
    private void renderEmpty(String message){if(summary!=null){summary.setText(message);signalSummary.setText("");if(skyPlot!=null)skyPlot.setSatellites(Collections.emptyList());rows.removeAllViews();}}

    private void render(int visible,int used,int strong,int fair,int weak,float avg){
        summary.setText("Visible: "+visible+"     Used for GPS fix: "+used);
        signalSummary.setText(String.format(Locale.getDefault(),"Strong %d   •   Fair %d   •   Weak %d   •   Average %.0f dB-Hz",strong,fair,weak,avg));
        Collections.sort(satellites,Comparator.comparingDouble((Sat s)->s.cn0).reversed());skyPlot.setSatellites(satellites);rows.removeAllViews();
        if(satellites.isEmpty()){TextView empty=new TextView(this);empty.setText("Searching for satellites… Try outdoors with a clear view of the sky.");empty.setTextColor(Color.LTGRAY);empty.setTextSize(15);empty.setPadding(0,dp(15),0,dp(10));rows.addView(empty);return;}
        for(Sat sat:satellites){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(10),dp(10),dp(10),dp(8));row.setBackgroundColor(Color.rgb(24,35,51));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(86));rp.bottomMargin=dp(7);rows.addView(row,rp);
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);TextView id=new TextView(this);id.setText(sat.system+"  •  SVID "+sat.id);id.setTextColor(Color.WHITE);id.setTextSize(15);id.setTypeface(null,android.graphics.Typeface.BOLD);top.addView(id,new LinearLayout.LayoutParams(0,dp(24),1));TextView strength=new TextView(this);strength.setText(String.format(Locale.getDefault(),"%.0f dB-Hz%s",sat.cn0,sat.used?"  •  IN USE":""));strength.setTextColor(sat.cn0>=30?Color.rgb(90,220,140):sat.cn0>=20?Color.rgb(255,205,90):Color.rgb(255,145,120));strength.setTextSize(12);top.addView(strength);row.addView(top);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(50);bar.setProgress(Math.min(50,Math.round(sat.cn0)));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(6));bp.topMargin=dp(4);row.addView(bar,bp);
            TextView angles=new TextView(this);angles.setText(String.format(Locale.getDefault(),"Elevation %.0f°   •   Azimuth %.0f°",sat.elevation,sat.azimuth));angles.setTextColor(Color.LTGRAY);angles.setTextSize(12);angles.setPadding(0,dp(5),0,0);row.addView(angles);
        }
    }

    private final class SkyPlotView extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<Sat> items=new ArrayList<>();
        SkyPlotView(Context context){super(context);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void setSatellites(java.util.List<Sat> values){items.clear();items.addAll(values);invalidate();}
        private void label(Canvas c,String text,float x,float y,float size,int color,Paint.Align align){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(dp((int)size));paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));paint.setTextAlign(align);c.drawText(text,x,y,paint);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float w=getWidth(),h=getHeight();canvas.drawColor(Color.rgb(24,35,51));
            label(canvas,"SATELLITE SKY VIEW  •  center = overhead",w/2,dp(18),12,Color.LTGRAY,Paint.Align.CENTER);
            float cx=w/2,cy=h/2+dp(8),radius=Math.min(w*.34f,h*.34f);
            paint.setColor(Color.rgb(92,112,135));paint.setStrokeWidth(dp(1));paint.setStyle(Paint.Style.STROKE);
            for(int ring=1;ring<=3;ring++)canvas.drawCircle(cx,cy,radius*ring/3f,paint);
            canvas.drawLine(cx-radius,cy,cx+radius,cy,paint);canvas.drawLine(cx,cy-radius,cx,cy+radius,paint);
            label(canvas,"N",cx,cy-radius-dp(8),12,Color.WHITE,Paint.Align.CENTER);
            label(canvas,"E",cx+radius+dp(13),cy+dp(4),12,Color.WHITE,Paint.Align.CENTER);
            label(canvas,"S",cx,cy+radius+dp(17),12,Color.WHITE,Paint.Align.CENTER);
            label(canvas,"W",cx-radius-dp(13),cy+dp(4),12,Color.WHITE,Paint.Align.CENTER);
            label(canvas,"30°",cx+dp(4),cy-radius*2/3f+dp(4),9,Color.LTGRAY,Paint.Align.LEFT);
            label(canvas,"60°",cx+dp(4),cy-radius/3f+dp(4),9,Color.LTGRAY,Paint.Align.LEFT);
            for(Sat sat:items){
                if(Float.isNaN(sat.azimuth)||Float.isNaN(sat.elevation))continue;
                double az=Math.toRadians(sat.azimuth),elev=Math.max(0,Math.min(90,sat.elevation));
                float r=radius*(1f-(float)elev/90f),x=cx+(float)Math.sin(az)*r,y=cy-(float)Math.cos(az)*r;
                int color=sat.cn0>=30?Color.rgb(68,220,135):sat.cn0>=20?Color.rgb(255,199,75):Color.rgb(255,135,115);
                if(sat.used){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(9),paint);}
                paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawCircle(x,y,dp(6),paint);
                label(canvas,String.valueOf(sat.id),x+dp(8),y+dp(4),9,Color.WHITE,Paint.Align.LEFT);
            }
        }
    }
    private String name(int c){switch(c){case GnssStatus.CONSTELLATION_GPS:return "GPS";case GnssStatus.CONSTELLATION_GLONASS:return "GLONASS";case GnssStatus.CONSTELLATION_GALILEO:return "Galileo";case GnssStatus.CONSTELLATION_BEIDOU:return "BeiDou";case GnssStatus.CONSTELLATION_QZSS:return "QZSS";case GnssStatus.CONSTELLATION_SBAS:return "SBAS";case GnssStatus.CONSTELLATION_IRNSS:return "NavIC";default:return "GNSS";}}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onDestroy(){super.onDestroy();try{if(Build.VERSION.SDK_INT>=24)locationManager.unregisterGnssStatusCallback(callback);}catch(Exception ignored){}}
    private static class Sat{final String system;final int id;final float cn0, elevation,azimuth;final boolean used;Sat(String n,int i,float c,boolean u,float e,float a){system=n;id=i;cn0=c;used=u;elevation=e;azimuth=a;}}
}

