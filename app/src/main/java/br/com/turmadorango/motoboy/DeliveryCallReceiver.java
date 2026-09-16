package br.com.turmadorango.motoboy;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class DeliveryCallReceiver extends BroadcastReceiver {
    public static final String ACTION_ACCEPT = "br.com.turmadorango.motoboy.CALL_ACCEPT";
    public static final String ACTION_DECLINE = "br.com.turmadorango.motoboy.CALL_DECLINE";

    @Override public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (!ACTION_ACCEPT.equals(a) && !ACTION_DECLINE.equals(a)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final int callId = intent.getIntExtra("call_id", 0);
        final String token = intent.getStringExtra("token");
        final String action = ACTION_ACCEPT.equals(a) ? "accept" : "decline";

        DeliveryCallManager.stopCurrentAlert(app, callId);
        new Thread(() -> {
            try { respond(app, callId, token == null ? "" : token, action); }
            finally { pending.finish(); }
        }, "tdr-call-action").start();
    }

    private void respond(Context context, int callId, String offerToken, String action) {
        if (callId <= 0 || offerToken.isEmpty()) return;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DeliveryCallManager.CALL_URL + "?action=" + action);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "TurmaDoRangoMotoboyApp/" + BuildConfig.VERSION_NAME);

            String appToken = context.getSharedPreferences("tdr_app_auth", Context.MODE_PRIVATE)
                    .getString("app_token", "").trim();
            if (!appToken.isEmpty()) {
                conn.setRequestProperty("X-TDR-App-Token", appToken);
            } else {
                String cookie = CookieManager.getInstance().getCookie("https://turmadorango.com.br/includes/motoboy/");
                if (cookie != null && !cookie.trim().isEmpty()) conn.setRequestProperty("Cookie", cookie);
            }

            String body = "call_id=" + URLEncoder.encode(String.valueOf(callId), "UTF-8")
                    + "&token=" + URLEncoder.encode(offerToken, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) result.append(line);
            r.close();

            JSONObject data = new JSONObject(result.toString());
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(DeliveryCallManager.notificationId(callId));

            if (data.optBoolean("ok", false) && data.optBoolean("accepted", false)) {
                int pedidoId = data.optInt("pedido_id", 0);
                String text = pedidoId > 0
                        ? "Pedido #" + pedidoId + " já está no seu painel. Toque para iniciar a entrega."
                        : "A entrega já está no seu painel.";
                DeliveryCallManager.showResultNotification(context, "✅ ENTREGA ACEITA", text);
                Intent changed = new Intent(RealtimeService.ACTION_CHANGED);
                changed.setPackage(context.getPackageName());
                changed.putExtra("revision", "call-accepted-" + callId);
                context.sendBroadcast(changed);
            } else if (data.optBoolean("ok", false)) {
                DeliveryCallManager.showResultNotification(context, "Chamada passada", "A entrega foi enviada para o próximo motoboy online.");
            } else {
                DeliveryCallManager.showResultNotification(context, "Chamada encerrada",
                        data.optString("message", "Esta chamada já não está disponível."));
            }
        } catch (Exception e) {
            DeliveryCallManager.showResultNotification(context, "Falha ao responder",
                    "Confira a internet e tente novamente se a chamada ainda estiver disponível.");
        } finally {
            if (conn != null) conn.disconnect();
            DeliveryCallManager.kick(context);
        }
    }
}
