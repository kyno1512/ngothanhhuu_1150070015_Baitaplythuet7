package com.example.bt_7;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;

public class DownloadService extends Service {

    // Điều khiển từ notification
    public enum Cmd { PAUSE, RESUME, CANCEL }
    private static final ArrayBlockingQueue<Cmd> q = new ArrayBlockingQueue<>(1);
    public static void signal(Cmd c){ q.offer(c); }

    // Trạng thái
    private volatile boolean paused=false, canceled=false;
    private String url;
    private File file;
    private long downloaded=0, total=-1;

    @Override public IBinder onBind(Intent intent){ return null; }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        url = intent.getStringExtra(DL.EXTRA_URL);
        Log.d("DL","onStartCommand url=" + url);
        if (url==null || url.isEmpty()) return START_NOT_STICKY;

        createChannel();
        startForeground(DL.NOTI_ID, buildNoti(0,false));

        // Đặt tên file theo URL. Lưu ở ExternalFiles để dễ kiểm tra.
        String name="download.bin";
        try {
            String p=new URL(url).getPath(); int i=p.lastIndexOf('/');
            if(i>=0 && i+1<p.length()) name=p.substring(i+1);
        } catch(Exception ignore){}
        File dir = getExternalFilesDir(null);
        if (dir==null) dir=getFilesDir();
        file = new File(dir, name);

        new Thread(this::loop).start();
        new Thread(this::listen).start();
        return START_NOT_STICKY;
    }

    private void listen(){
        try{
            while(true){
                Cmd c=q.take();
                if(c==Cmd.PAUSE){ paused=true; notifyProgress(percent(),true); }
                if(c==Cmd.RESUME){ paused=false; notifyProgress(percent(),false); }
                if(c==Cmd.CANCEL){ canceled=true; stopSelf(); return; }
            }
        }catch(InterruptedException ignored){}
    }

    // THROTTLE: chậm tiến độ để nhìn thấy noti rõ
    private void loop(){
        try{
            if(file.exists()) downloaded=file.length();
            while(true){
                if(canceled){ file.delete(); return; }
                if(paused){ Thread.sleep(200); continue; }

                HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
                if(downloaded>0) conn.setRequestProperty("Range","bytes="+downloaded+"-");
                conn.connect();
                Log.d("DL","HTTP " + conn.getResponseCode());

                if(total<=0){
                    String cr=conn.getHeaderField("Content-Range");
                    String cl=conn.getHeaderField("Content-Length");
                    if(cr!=null && cr.contains("/")) total=parseLongSafe(cr.substring(cr.lastIndexOf('/')+1));
                    if(total<=0) total=parseLongSafe(cl);
                }

                try(InputStream in=conn.getInputStream();
                    RandomAccessFile raf=new RandomAccessFile(file,"rw")){
                    if(downloaded>0) raf.seek(downloaded);

                    // Buffer nhỏ + sleep để chậm
                    byte[] buf=new byte[4 * 1024]; // 4KB
                    while(true){
                        if(canceled){ file.delete(); return; }
                        if(paused){ break; }

                        int r=in.read(buf);
                        if(r==-1){
                            notifyProgress(100,false);
                            // Giữ noti 5s để người dùng kịp thấy 100%
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                            Log.d("DL","DONE");
                            return;
                        }
                        raf.write(buf,0,r);
                        downloaded+=r;
                        notifyProgress(percent(),false);

                        // Làm chậm rõ rệt
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    }
                } finally { conn.disconnect(); }
            }
        }catch(Exception e){
            Log.e("DL","ERROR", e);
            notifyFail();
        } finally { stopSelf(); }
    }

    private void notifyProgress(int pct, boolean isPaused){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(DL.NOTI_ID, buildNoti(pct,isPaused));
    }

    // Notification custom: Link + % + Pause/Resume + Cancel
    private Notification buildNoti(int pct, boolean isPaused){
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.noti_download);
        rv.setTextViewText(R.id.tvUrl, "Link: " + url);
        rv.setTextViewText(R.id.tvProgress, pct>=0 ? ("Complete: " + pct + "%") : "Starting…");
        rv.setTextViewText(R.id.btnPrimary, isPaused ? "Resume" : "Pause");

        PendingIntent piPrimary = PendingIntent.getBroadcast(
                this, 1, new Intent(this, ControlReceiver.class)
                        .setAction(isPaused ? DL.ACTION_RESUME : DL.ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piCancel = PendingIntent.getBroadcast(
                this, 2, new Intent(this, ControlReceiver.class)
                        .setAction(DL.ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        rv.setOnClickPendingIntent(R.id.btnPrimary, piPrimary);
        rv.setOnClickPendingIntent(R.id.btnSecondary, piCancel);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, DL.CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setOnlyAlertOnce(true)
                .setOngoing(pct>=0 && pct<100);
        if (Build.VERSION.SDK_INT >= 34) {
            b.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(
                    DL.CH_ID,"Downloads",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Foreground downloads");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private int percent(){ return total>0 ? (int)Math.max(0, Math.min(100,(downloaded*100L)/total)) : 0; }
    private static long parseLongSafe(String s){ try{ return Long.parseLong(s); }catch(Exception e){ return -1; } }
    private void notifyFail(){ notifyProgress(-1,false); }
}
