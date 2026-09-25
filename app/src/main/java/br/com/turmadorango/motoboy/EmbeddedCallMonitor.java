package br.com.turmadorango.motoboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ÚNICO monitor de chamadas. Consulta app_call_poll.php e entrega a oferta
 * diretamente ao DeliveryCallManager, sem iniciar outro polling paralelo.
 */
public final class EmbeddedCallMonitor {
    private static final String POLL_URL =
            "https://turmadorango.com.br/includes/motoboy/app_call_poll.php";
    private static final long POLL_MS = 700L;
    private static EmbeddedCallMonitor instance;

    private final Context app;
    private final SharedPreferences auth;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean working;
    private boolean running;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (working) {
                handler.postDelayed(this, 220L);
                return;
            }

            working = true;
            new Thread(() -> {
                long next = POLL_MS;
                try {
                    next = pollOnce();
                } catch (Exception ignored) {
                    saveState("erro_rede");
                    next = 1500L;
                } finally {
                    working = false;
                    if (running) {
                        handler.postDelayed(pollRunnable, Math.max(500L, next));
                    }
                }
            }, "tdr-embedded-call-monitor").start();
        }
    };

    private EmbeddedCallMonitor(Context context) {
        app = context.getApplicationContext();
        auth = app.getSharedPreferences("tdr_app_auth", Context.MODE_PRIVATE);
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new EmbeddedCallMonitor(context);
        instance.running = true;
        instance.handler.removeCallbacks(instance.pollRunnable);
        instance.handler.postDelayed(instance.pollRunnable, 180L);
    }

    public static synchronized void kick(Context context) {
        start(context);
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pollRunnable);
            instance.handler.postDelayed(instance.pollRunnable, 30L);
        }
    }

    private String token() {
        return auth.getString("app_token", "").trim();
    }

    private void saveState(String state) {
        auth.edit()
                .putString("embedded_call_state", state)
                .putLong("embedded_call_checked_at", System.currentTimeMillis())
                .apply();
    }

    private long pollOnce() throws Exception {
        String appToken = token();
        if (appToken.isEmpty()) {
            saveState("aguardando_token");
            NativeAuthSync.syncNow(app);
            return 900L;
        }

        HttpURLConnection conn = null;
        try {
            String endpoint = POLL_URL
                    + "?app_token=" + URLEncoder.encode(appToken, "UTF-8")
                    + "&app_version=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
                    + "&t=" + System.currentTimeMillis();

            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-TDR-App-Token", appToken);
            conn.setRequestProperty("X-TDR-App-Version", BuildConfig.VERSION_NAME);
            conn.setRequestProperty("User-Agent",
                    "UniBoyEntregas/" + BuildConfig.VERSION_NAME);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                DeliveryCallManager.clearCurrent(app);
                saveState("reautenticando");
                NativeAuthSync.invalidateAndSync(app);
                return 900L;
            }
            if (code < 200 || code >= 300) {
                saveState("http_" + code);
                return 1500L;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            JSONObject data = new JSONObject(body.toString());
            if (!data.optBoolean("ok", false) || !data.optBoolean("logged", false)) {
                DeliveryCallManager.clearCurrent(app);
                saveState("resposta_nao_autenticada");
                NativeAuthSync.invalidateAndSync(app);
                return 900L;
            }

            auth.edit()
                    .putString("embedded_call_state", "conectado")
                    .putLong("embedded_call_ok_at", System.currentTimeMillis())
                    .putInt("embedded_call_motoboy_id", data.optInt("motoboy_id", 0))
                    .apply();

            JSONObject offer = data.optJSONObject("offer");
            if (offer != null
                    && offer.optInt("id", 0) > 0
                    && !offer.optString("token", "").isEmpty()) {
                DeliveryCallManager.presentOffer(app, offer);
                return 500L;
            }

            DeliveryCallManager.clearCurrent(app);
            return Math.max(500L, data.optLong("poll_ms", POLL_MS));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private EmbeddedCallMonitor() { throw new AssertionError(); }
}
