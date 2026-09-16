package br.com.turmadorango.motoboy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class DeliveryCallActivity extends Activity {
    private int callId;
    private int pedidoId;
    private String token;
    private int totalSeconds;
    private TextView timerText;
    private ProgressBar timerBar;
    private CountDownTimer timer;
    private boolean answered = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureCallWindow();

        readIntent(getIntent());
        CallOverlayManager.hide(this, callId);
        if (callId <= 0 || token == null || token.isEmpty()) {
            finish();
            return;
        }
        setContentView(buildUi());
        startTimer();
    }

    @Override protected void onResume() {
        super.onResume();
        configureCallWindow();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        configureCallWindow();
        readIntent(intent);
        CallOverlayManager.hide(this, callId);
        answered = false;
        setContentView(buildUi());
        startTimer();
    }

    private void configureCallWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void readIntent(Intent i) {
        callId = i.getIntExtra("call_id", 0);
        pedidoId = i.getIntExtra("pedido_id", 0);
        token = i.getStringExtra("token");
        totalSeconds = Math.max(1, i.getIntExtra("seconds_left", 20));
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050505);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF111111);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(2), 0xFFFFC400);
        card.setBackground(bg);

        TextView badge = text("NOVA ENTREGA", 14, 0xFF050505, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xFFFFC400);
        badgeBg.setCornerRadius(dp(50));
        badge.setBackground(badgeBg);
        card.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("🏍 CHAMADA DE ENTREGA", 25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = lpMatch();
        titleLp.topMargin = dp(20);
        card.addView(title, titleLp);

        String street = getIntent().getStringExtra("rua");
        String district = getIntent().getStringExtra("bairro");
        if (street == null || street.trim().isEmpty()) street = "Rua não informada";
        if (district == null || district.trim().isEmpty()) district = "Bairro não informado";

        TextView address = text("📍 " + street, 21, Color.WHITE, true);
        address.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aLp = lpMatch();
        aLp.topMargin = dp(28);
        card.addView(address, aLp);

        TextView neighborhood = text("🏘 " + district, 18, 0xFFCCCCCC, false);
        neighborhood.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nLp = lpMatch();
        nLp.topMargin = dp(9);
        card.addView(neighborhood, nLp);

        TextView hint = text(
                "Aceite antes do tempo acabar. Se não aceitar, a chamada seguirá para o próximo motoboy online.",
                14,
                0xFFAAAAAA,
                false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hLp = lpMatch();
        hLp.topMargin = dp(22);
        card.addView(hint, hLp);

        timerBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        timerBar.setMax(totalSeconds);
        timerBar.setProgress(totalSeconds);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12));
        barLp.topMargin = dp(22);
        card.addView(timerBar, barLp);

        timerText = text(totalSeconds + "s", 34, 0xFFFFC400, true);
        timerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = lpMatch();
        tLp.topMargin = dp(8);
        card.addView(timerText, tLp);

        Button accept = new Button(this);
        accept.setText("ACEITAR ENTREGA");
        accept.setTextSize(18);
        accept.setTextColor(Color.BLACK);
        accept.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable acceptBg = new GradientDrawable();
        acceptBg.setColor(0xFFFFC400);
        acceptBg.setCornerRadius(dp(16));
        accept.setBackground(acceptBg);
        accept.setOnClickListener(v -> respond(true));
        LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58));
        acceptLp.topMargin = dp(22);
        card.addView(accept, acceptLp);

        Button decline = new Button(this);
        decline.setText("PASSAR PARA O PRÓXIMO");
        decline.setTextSize(15);
        decline.setTextColor(Color.WHITE);
        decline.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable declineBg = new GradientDrawable();
        declineBg.setColor(0xFF2A2A2A);
        declineBg.setCornerRadius(dp(16));
        declineBg.setStroke(dp(1), 0xFF555555);
        decline.setBackground(declineBg);
        decline.setOnClickListener(v -> respond(false));
        LinearLayout.LayoutParams declineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54));
        declineLp.topMargin = dp(12);
        card.addView(decline, declineLp);

        TextView footer = text("Turma do Rango • Motoboy", 13, 0xFF777777, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = lpMatch();
        fLp.topMargin = dp(20);
        card.addView(footer, fLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);
        return root;
    }

    private void startTimer() {
        if (timer != null) timer.cancel();
        timer = new CountDownTimer(totalSeconds * 1000L, 250L) {
            @Override public void onTick(long millisUntilFinished) {
                int s = Math.max(0, (int)Math.ceil(millisUntilFinished / 1000.0));
                if (timerText != null) timerText.setText(s + "s");
                if (timerBar != null) timerBar.setProgress(s);
            }

            @Override public void onFinish() {
                if (!answered) {
                    DeliveryCallManager.stopCurrentAlert(DeliveryCallActivity.this, callId);
                    finish();
                }
            }
        }.start();
    }

    private void respond(boolean accept) {
        if (answered) return;
        answered = true;
        if (timer != null) timer.cancel();
        DeliveryCallManager.stopCurrentAlert(this, callId);

        Intent i = new Intent(this, DeliveryCallReceiver.class);
        i.setAction(accept
                ? DeliveryCallReceiver.ACTION_ACCEPT
                : DeliveryCallReceiver.ACTION_DECLINE);
        i.putExtra("call_id", callId);
        i.putExtra("pedido_id", pedidoId);
        i.putExtra("token", token);
        sendBroadcast(i);
        finish();
    }

    @Override public void onBackPressed() {
        if (answered) super.onBackPressed();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        if (timer != null) timer.cancel();
        super.onDestroy();
    }
}
