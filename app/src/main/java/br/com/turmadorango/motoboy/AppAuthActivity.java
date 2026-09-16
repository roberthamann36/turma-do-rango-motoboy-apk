package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

public class AppAuthActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() != null ? getIntent().getData() : null;
        String token = data != null ? data.getQueryParameter("token") : null;
        String motoboyId = data != null ? data.getQueryParameter("motoboy_id") : null;

        if (token != null && token.trim().length() >= 40) {
            getSharedPreferences("tdr_app_auth", MODE_PRIVATE)
                    .edit()
                    .putString("app_token", token.trim())
                    .putString("motoboy_id", motoboyId == null ? "" : motoboyId)
                    .apply();
            DeliveryCallManager.kick(this);
            try {
                Intent service = new Intent(this, RealtimeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                else startService(service);
            } catch (Exception ignored) {}
        }
        finish();
    }
}
