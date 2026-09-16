package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Solicita uma única vez por versão as permissões especiais necessárias para
 * uma chamada de entrega aparecer como chamada telefônica, mesmo quando outro
 * aplicativo estiver na frente.
 */
public final class CallPermissionManager {
    private static final String PREFS = "tdr_call_permissions";

    public static void ensure(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int version = BuildConfig.VERSION_CODE;

        // Android 14+: a permissão de full-screen intent passou a ter uma chave
        // própria em Acesso especial. Sem ela o sistema reduz a chamada a um aviso.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                NotificationManager nm = (NotificationManager)
                        activity.getSystemService(Context.NOTIFICATION_SERVICE);
                boolean allowed = nm != null && nm.canUseFullScreenIntent();
                if (!allowed && prefs.getInt("fsi_prompt_version", 0) != version) {
                    prefs.edit().putInt("fsi_prompt_version", version).apply();
                    Toast.makeText(
                            activity,
                            "Ative 'Permitir notificações em tela cheia' para as chamadas abrirem como telefone.",
                            Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // A sobreposição é o fallback confiável quando o Android bloqueia a
        // abertura de Activity em segundo plano. Ela permite mostrar o card de
        // chamada imediatamente por cima do app que o motoboy estiver usando.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (!Settings.canDrawOverlays(activity)
                        && prefs.getInt("overlay_prompt_version", 0) != version) {
                    prefs.edit().putInt("overlay_prompt_version", version).apply();
                    Toast.makeText(
                            activity,
                            "Ative 'Permitir sobre outros apps' para a chamada aparecer automaticamente na frente.",
                            Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    private CallPermissionManager() { throw new AssertionError(); }
}
