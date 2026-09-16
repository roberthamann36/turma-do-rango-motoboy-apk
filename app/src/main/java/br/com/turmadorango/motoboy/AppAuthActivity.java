package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class AppAuthActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        String token = data != null ? data.getQueryParameter("token") : null;
        String motoboyId = data != null ? data.getQueryParameter("motoboy_id") : null;

        if (token != null && token.trim().length() >= 40) {
            token = token.trim();
            SharedPreferences prefs = getSharedPreferences("tdr_app_auth", MODE_PRIVATE);
            String previous = prefs.getString("app_token", "");
            boolean changed = !token.equals(previous);

            prefs.edit()
                    .putString("app_token", token)
                    .putString("motoboy_id", motoboyId == null ? "" : motoboyId)
                    .putLong("linked_at", System.currentTimeMillis())
                    .commit();

            DeliveryCallManager.start(this);
            EmbeddedCallMonitor.kick(this);

            try {
                Intent service = new Intent(this, RealtimeService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                else startService(service);
            } catch (Exception ignored) {}

            if (changed) {
                Toast.makeText(this, "Chamadas do motoboy ativadas neste celular.", Toast.LENGTH_LONG).show();
            }
        }

        finish();
    }
}
