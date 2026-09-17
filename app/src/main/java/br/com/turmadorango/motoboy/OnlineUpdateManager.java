package br.com.turmadorango.motoboy;

import android.content.Context;
import android.content.Intent;
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
 * Atualizador leve da camada online do aplicativo.
 *
 * Não baixa nem instala APK. O servidor mantém um número de versão da interface.
 * Quando esse número muda, o WebView já aberto é recarregado pela aplicação,
 * preservando o APK/base Android instalada.
 *
 * Se o app estiver em segundo plano, apenas marca a atualização como pendente;
 * a interface será recarregada quando o motoboy voltar ao app. Este componente
 * NUNCA abre Activity e portanto não tira o usuário do WhatsApp ou de outro app.
 */
public final class OnlineUpdateManager {
    private static final String VERSION_URL =
            "https://turmadorango.com.br/includes/motoboy/app-online-version.php";
    private static final String PREFS = "tdr_online_update";
    private static final long POLL_MS = 20_000L;
    private static final long ERROR_POLL_MS = 60_000L;

    private static OnlineUpdateManager instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean checking = false;
    private long nextDelay = POLL_MS;

    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            checkNow();
        }
    };

    private OnlineUpdateManager(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        handler.postDelayed(periodic, 3500L);
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new OnlineUpdateManager(context);
    }

    public static synchronized void check(Context context) {
        start(context);
        if (instance != null) instance.handler.post(instance::checkNow);
    }

    private void schedule() {
        handler.removeCallbacks(periodic);
        handler.postDelayed(periodic, Math.max(5000L, nextDelay));
    }

    private void checkNow() {
        if (checking) {
            schedule();
            return;
        }

        String token = app.getSharedPreferences("tdr_app_auth", Context.MODE_PRIVATE)
                .getString("app_token", "");
        if (token == null || token.trim().isEmpty()) {
            nextDelay = 15_000L;
            schedule();
            return;
        }

        checking = true;
        final String appToken = token.trim();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String url = VERSION_URL
                        + "?app_token=" + URLEncoder.encode(appToken, "UTF-8")
                        + "&base_code=" + BuildConfig.VERSION_CODE
                        + "&t=" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("X-TDR-App-Token", appToken);
                conn.setRequestProperty("X-TDR-App-Version", BuildConfig.VERSION_NAME);
                conn.setRequestProperty("User-Agent",
                        "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    nextDelay = ERROR_POLL_MS;
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) body.append(line);
                }

                JSONObject data = new JSONObject(body.toString());
                if (!data.optBoolean("ok", false)) {
                    nextDelay = ERROR_POLL_MS;
                    return;
                }

                int remoteVersion = data.optInt("interface_version", 0);
                if (remoteVersion <= 0) {
                    nextDelay = ERROR_POLL_MS;
                    return;
                }

                int previousVersion = prefs.getInt("interface_version", 0);
                prefs.edit()
                        .putInt("interface_version", remoteVersion)
                        .putString("published_at", data.optString("published_at", ""))
                        .putString("message", data.optString("message", ""))
                        .putLong("checked_at", System.currentTimeMillis())
                        .apply();

                // Primeira leitura estabelece a base sem recarregar a tela à toa.
                if (previousVersion > 0 && remoteVersion > previousVersion) {
                    Intent changed = new Intent(RealtimeService.ACTION_CHANGED);
                    changed.setPackage(app.getPackageName());
                    changed.putExtra("online_update", true);
                    changed.putExtra("interface_version", remoteVersion);
                    app.sendBroadcast(changed);
                }

                nextDelay = POLL_MS;
            } catch (Exception ignored) {
                nextDelay = ERROR_POLL_MS;
            } finally {
                if (conn != null) conn.disconnect();
                checking = false;
                handler.post(this::schedule);
            }
        }, "tdr-online-update").start();
    }

    private OnlineUpdateManager() { throw new AssertionError(); }
}
