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
 * Monitor leve de chamadas que roda no MESMO processo mantido vivo pelo
 * RealtimeService. Não cria um segundo foreground service.
 *
 * O endpoint app_call_poll.php mantém app_online_at atualizado. Quando existe
 * uma oferta para este motoboy, o monitor acorda o DeliveryCallManager, que
 * faz o toque/vibração e abre a tela de aceitar/passar.
 */
public final class EmbeddedCallMonitor {
    private static final String POLL_URL =
            "https://turmadorango.com.br/includes/motoboy/app_call_poll.php";
    private static final long POLL_MS = 750L;
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
                handler.postDelayed(this, 250L);
                return;
            }

            working = true;
            new Thread(() -> {
                long next = POLL_MS;
                try {
                    next = pollOnce();
                } catch (Exception ignored) {
                    saveState("erro_rede");
                    next = 1600L;
                } finally {
                    working = false;
                    if (running) {
                        handler.postDelayed(pollRunnable, Math.max(550L, next));
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
        instance.handler.postDelayed(instance.pollRunnable, 250L);
    }

    public static synchronized void kick(Context context) {
        start(context);
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pollRunnable);
            instance.handler.postDelayed(instance.pollRunnable, 40L);
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
            return 1000L;
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
                    "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);

            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                saveState("reautenticando");
                NativeAuthSync.invalidateAndSync(app);
                return 1000L;
            }
            if (code < 200 || code >= 300) {
                saveState("http_" + code);
                return 1600L;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            JSONObject data = new JSONObject(body.toString());
            if (!data.optBoolean("ok", false) || !data.optBoolean("logged", false)) {
                saveState("resposta_nao_autenticada");
                NativeAuthSync.invalidateAndSync(app);
                return 1000L;
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
                // Acorda imediatamente o módulo que toca/vibra e mostra a chamada.
                DeliveryCallManager.kick(app);
                return 550L;
            }

            return Math.max(550L, data.optLong("poll_ms", POLL_MS));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private EmbeddedCallMonitor() { throw new AssertionError(); }
}
