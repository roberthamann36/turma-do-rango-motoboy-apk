package br.com.turmadorango.motoboy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mantém o token nativo do APK sincronizado com a sessão já autenticada do WebView.
 * Assim as chamadas de entrega continuam funcionando mesmo com o WebView em segundo plano.
 */
public final class NativeAuthSync {
    private static final String BASE = "https://turmadorango.com.br/includes/motoboy/";
    private static final String TOKEN_URL = BASE + "app_token.php";
    private static final String PREFS = "tdr_app_auth";
    private static final long RETRY_MS = 2500L;
    private static final long REFRESH_MS = 30000L;

    private static NativeAuthSync instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean syncing = false;

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            sync();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private NativeAuthSync(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        main.postDelayed(periodic, 1200L);
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new NativeAuthSync(context);
    }

    public static synchronized void syncNow(Context context) {
        start(context);
        if (instance != null) instance.main.post(instance::sync);
    }

    public static synchronized void invalidateAndSync(Context context) {
        start(context);
        if (instance == null) return;
        instance.prefs.edit().remove("app_token").apply();
        instance.main.post(instance::sync);
    }

    private void sync() {
        if (syncing) return;

        String cookie = "";
        try {
            String c = CookieManager.getInstance().getCookie(BASE);
            if (c != null) cookie = c.trim();
        } catch (Exception ignored) {}

        if (cookie.isEmpty()) {
            prefs.edit()
                    .putString("native_auth_state", "aguardando_sessao")
                    .putLong("native_auth_checked_at", System.currentTimeMillis())
                    .apply();
            main.postDelayed(this::sync, RETRY_MS);
            return;
        }

        syncing = true;
        final String sessionCookie = cookie;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(TOKEN_URL + "?native=1&t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Cookie", sessionCookie);
                conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);
                conn.setRequestProperty("X-TDR-App-Version", BuildConfig.VERSION_NAME);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    prefs.edit()
                            .putString("native_auth_state", "http_" + code)
                            .putLong("native_auth_checked_at", System.currentTimeMillis())
                            .apply();
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                }

                JSONObject data = new JSONObject(body.toString());
                String token = data.optString("token", "").trim();
                int motoboyId = data.optInt("motoboy_id", 0);
                if (!data.optBoolean("ok", false) || token.length() < 40 || motoboyId <= 0) {
                    prefs.edit()
                            .putString("native_auth_state", "resposta_invalida")
                            .putLong("native_auth_checked_at", System.currentTimeMillis())
                            .apply();
                    return;
                }

                prefs.edit()
                        .putString("app_token", token)
                        .putString("motoboy_id", String.valueOf(motoboyId))
                        .putString("motoboy_nome", data.optString("motoboy_nome", ""))
                        .putString("native_auth_state", "ok")
                        .putLong("native_auth_ok_at", System.currentTimeMillis())
                        .putLong("native_auth_checked_at", System.currentTimeMillis())
                        .apply();

                DeliveryCallManager.kick(app);
            } catch (Exception e) {
                prefs.edit()
                        .putString("native_auth_state", "erro_rede")
                        .putLong("native_auth_checked_at", System.currentTimeMillis())
                        .apply();
            } finally {
                if (conn != null) conn.disconnect();
                syncing = false;
            }
        }, "tdr-native-auth-sync").start();
    }

    private NativeAuthSync() { throw new AssertionError(); }
}
