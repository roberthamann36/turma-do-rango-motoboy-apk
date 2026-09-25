package br.com.turmadorango.motoboy;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Tela de chamada por sobreposição. É usada como fallback quando o Android não
 * permite abrir uma Activity diretamente a partir do segundo plano.
 */
public final class CallOverlayManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WindowManager windowManager;
    private static View rootView;
    private static int currentCallId;
    private static int currentPedidoId;
    private static String currentToken = "";
    private static long endAtElapsed;
    private static TextView timerText;
    private static ProgressBar timerBar;

    private static final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (rootView == null || currentCallId <= 0) return;
            long remainingMs = Math.max(0L, endAtElapsed - SystemClock.elapsedRealtime());
            int seconds = (int)Math.ceil(remainingMs / 1000.0);
            if (timerText != null) timerText.setText(seconds + "s");
            if (timerBar != null) timerBar.setProgress(seconds);
            if (remainingMs <= 0L) {
                hideInternal(0);
            } else {
                MAIN.postDelayed(this, 250L);
            }
        }
    };

    public static boolean show(Context context, JSONObject offer) {
        if (context == null || offer == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (!Settings.canDrawOverlays(context)) return false;

        Context app = context.getApplicationContext();
        JSONObject copy;
        try { copy = new JSONObject(offer.toString()); }
        catch (Exception e) { copy = offer; }
        JSONObject finalOffer = copy;
        MAIN.post(() -> showInternal(app, finalOffer));
        return true;
    }

    public static void hide(Context context, int callId) {
        MAIN.post(() -> hideInternal(callId));
    }

    private static void showInternal(Context app, JSONObject offer) {
        int callId = offer.optInt("id", 0);
        String token = offer.optString("token", "");
        if (callId <= 0 || token.isEmpty()) return;
        if (rootView != null && currentCallId == callId && token.equals(currentToken)) return;

        hideInternal(0);

        currentCallId = callId;
        currentPedidoId = offer.optInt("pedido_id", 0);
        currentToken = token;
        int seconds = Math.max(1, offer.optInt("seconds_left", 20));
        endAtElapsed = SystemClock.elapsedRealtime() + seconds * 1000L;

        String street = offer.optString("rua", "Rua não informada");
        String district = offer.optString("bairro", "Bairro não informado");

        FrameLayout root = new FrameLayout(app);
        root.setBackgroundColor(0xF7050505);
        root.setPadding(dp(app, 20), dp(app, 24), dp(app, 20), dp(app, 24));
        root.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(app, 24), dp(app, 28), dp(app, 24), dp(app, 24));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF111111);
        cardBg.setCornerRadius(dp(app, 28));
        cardBg.setStroke(dp(app, 2), 0xFFFFC400);
        card.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) card.setElevation(dp(app, 18));

        TextView badge = text(app, "NOVA ENTREGA", 14, 0xFF050505, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(app, 14), dp(app, 7), dp(app, 14), dp(app, 7));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFFFFC400);
        badgeBg.setCornerRadius(dp(app, 50));
        badge.setBackground(badgeBg);
        card.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(app, "🏍 CHAMADA DE ENTREGA", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = match();
        titleLp.topMargin = dp(app, 20);
        card.addView(title, titleLp);

        TextView address = text(app, "📍 " + street, 21, Color.WHITE, true);
        address.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addressLp = match();
        addressLp.topMargin = dp(app, 28);
        card.addView(address, addressLp);

        TextView neighborhood = text(app, "🏘 " + district, 18, 0xFFCCCCCC, false);
        neighborhood.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams neighborhoodLp = match();
        neighborhoodLp.topMargin = dp(app, 9);
        card.addView(neighborhood, neighborhoodLp);

        TextView hint = text(
                app,
                "Aceite antes do tempo acabar. Se não aceitar, a chamada seguirá para o próximo motoboy online.",
                14,
                0xFFAAAAAA,
                false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = match();
        hintLp.topMargin = dp(app, 22);
        card.addView(hint, hintLp);

        timerBar = new ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal);
        timerBar.setMax(seconds);
        timerBar.setProgress(seconds);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(app, 12));
        barLp.topMargin = dp(app, 22);
        card.addView(timerBar, barLp);

        timerText = text(app, seconds + "s", 34, 0xFFFFC400, true);
        timerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timerLp = match();
        timerLp.topMargin = dp(app, 8);
        card.addView(timerText, timerLp);

        Button accept = new Button(app);
        accept.setText("ACEITAR ENTREGA");
        accept.setTextSize(18);
        accept.setTextColor(Color.BLACK);
        accept.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable acceptBg = new GradientDrawable();
        acceptBg.setColor(0xFFFFC400);
        acceptBg.setCornerRadius(dp(app, 16));
        accept.setBackground(acceptBg);
        accept.setOnClickListener(v -> respond(app, true));
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(app, 58));
        acceptLp.topMargin = dp(app, 22);
        card.addView(accept, acceptLp);

        Button decline = new Button(app);
        decline.setText("PASSAR PARA O PRÓXIMO");
        decline.setTextSize(15);
        decline.setTextColor(Color.WHITE);
        decline.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable declineBg = new GradientDrawable();
        declineBg.setColor(0xFF2A2A2A);
        declineBg.setCornerRadius(dp(app, 16));
        declineBg.setStroke(dp(app, 1), 0xFF555555);
        decline.setBackground(declineBg);
        decline.setOnClickListener(v -> respond(app, false));
        LinearLayout.LayoutParams declineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(app, 54));
        declineLp.topMargin = dp(app, 12);
        card.addView(decline, declineLp);

        TextView footer = text(app, "UNIBOY ENTREGAS", 13, 0xFF777777, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = match();
        footerLp.topMargin = dp(app, 20);
        card.addView(footer, footerLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            windowManager.addView(root, params);
            rootView = root;
            MAIN.removeCallbacks(timerRunnable);
            MAIN.post(timerRunnable);
        } catch (Exception ignored) {
            rootView = null;
            currentCallId = 0;
            currentPedidoId = 0;
            currentToken = "";
        }
    }

    private static void respond(Context app, boolean accept) {
        int callId = currentCallId;
        int pedidoId = currentPedidoId;
        String token = currentToken;
        if (callId <= 0 || token == null || token.isEmpty()) return;

        hideInternal(callId);
        DeliveryCallManager.stopCurrentAlert(app, callId);

        Intent i = new Intent(app, DeliveryCallReceiver.class);
        i.setAction(accept
                ? DeliveryCallReceiver.ACTION_ACCEPT
                : DeliveryCallReceiver.ACTION_DECLINE);
        i.putExtra("call_id", callId);
        i.putExtra("pedido_id", pedidoId);
        i.putExtra("token", token);
        app.sendBroadcast(i);
    }

    private static void hideInternal(int callId) {
        if (callId > 0 && currentCallId > 0 && callId != currentCallId) return;
        MAIN.removeCallbacks(timerRunnable);
        if (rootView != null && windowManager != null) {
            try { windowManager.removeViewImmediate(rootView); }
            catch (Exception ignored) {}
        }
        rootView = null;
        windowManager = null;
        timerText = null;
        timerBar = null;
        currentCallId = 0;
        currentPedidoId = 0;
        currentToken = "";
        endAtElapsed = 0L;
    }

    private static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private CallOverlayManager() { throw new AssertionError(); }
}
