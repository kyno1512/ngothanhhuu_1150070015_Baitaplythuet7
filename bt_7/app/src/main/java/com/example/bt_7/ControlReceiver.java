package com.example.bt_7;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (DL.ACTION_PAUSE.equals(a))  DownloadService.signal(DownloadService.Cmd.PAUSE);
        if (DL.ACTION_RESUME.equals(a)) DownloadService.signal(DownloadService.Cmd.RESUME);
        if (DL.ACTION_CANCEL.equals(a)) DownloadService.signal(DownloadService.Cmd.CANCEL);
    }
}
