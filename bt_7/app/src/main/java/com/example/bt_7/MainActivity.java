package com.example.bt_7;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private ActivityResultLauncher<String> notifPerm;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), g -> {});
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        Button btn = findViewById(R.id.btnDownload);
        EditText edt = findViewById(R.id.edtUrl);

        btn.setOnClickListener(v -> {
            String url = edt.getText().toString().trim();
            if (url.isEmpty()) return;

            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Cho phép Notifications", Toast.LENGTH_SHORT).show();
                if (notifPerm != null) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }

            Log.d("DL","CLICK: " + url);
            Intent i = new Intent(this, DownloadService.class);
            i.putExtra(DL.EXTRA_URL, url);
            ContextCompat.startForegroundService(this, i);
        });
    }
}
