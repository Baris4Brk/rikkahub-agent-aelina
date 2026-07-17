package me.rerere.rikkahub.assistantproxy;

import android.app.KeyguardManager;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Data-isolated tracer surface. It contains no RikkaHub IPC, model, database, or storage path.
 */
public final class ProxyVoiceInteractionSession extends VoiceInteractionSession {
    private final Context context;
    private TextView statusView;

    public ProxyVoiceInteractionSession(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public View onCreateContentView() {
        int padding = dp(24);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackground(panelBackground());

        TextView title = new TextView(context);
        title.setText(R.string.probe_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap(0));

        statusView = new TextView(context);
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        content.addView(statusView, matchWrap(dp(16)));

        Button close = new Button(context);
        close.setText(R.string.probe_close);
        close.setOnClickListener(view -> finish());
        content.addView(close, wrapWrap(dp(20)));

        renderCurrentLockState();
        return content;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        renderCurrentLockState();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onHandleAssist(
            Bundle data,
            AssistStructure structure,
            AssistContent content) {
        // Context capture is disabled in the VIS and discarded here as a second privacy floor.
    }

    @Override
    public void onHandleAssist(AssistState state) {
        // Context capture is disabled in the VIS and discarded here as a second privacy floor.
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onHandleAssistSecondary(
            Bundle data,
            AssistStructure structure,
            AssistContent content,
            int index,
            int count) {
        // Secondary assist context is deliberately ignored.
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        // Screenshots are disabled in the VIS and deliberately ignored here.
    }

    private void renderCurrentLockState() {
        if (statusView == null) {
            return;
        }
        ProbeUiPolicy.Mode mode = ProbeUiPolicy.modeForDeviceLocked(isDeviceLocked());
        int text = mode == ProbeUiPolicy.Mode.UNLOCK_REQUIRED
                ? R.string.probe_unlock_required
                : R.string.probe_data_isolated;
        statusView.setText(text);
    }

    private boolean isDeviceLocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked();
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(30, 30, 34));
        background.setCornerRadius(dp(24));
        return background;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
