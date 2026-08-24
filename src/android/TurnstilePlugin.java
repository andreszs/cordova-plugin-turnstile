package com.cordova.turnstile;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

public final class TurnstilePlugin extends CordovaPlugin {
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long TIMEOUT_MS = 120000L;
    private static final int BADGE_COMPACT_WIDTH_DP = 70;
    private static final int BADGE_EXPANDED_WIDTH_DP = 256;
    private static final int BADGE_HEIGHT_DP = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private WebView webView;
    private CallbackContext callback;
    private String expectedAction;
    private String expectedHost;
    private String expectedPath;
    private TurnstileBadgeView badgeView;
    private boolean finished;
    private long deadline;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("verify".equals(action)) {
            JSONObject options = args.optJSONObject(0);
            if (options == null) {
                callbackContext.error("invalid_options");
                return true;
            }
            String url = options.optString("url", "");
            String turnstileAction = options.optString("action", "");
            Uri initialUri = parseInitialUrl(url, turnstileAction);
            if (initialUri == null) {
                callbackContext.error("invalid_options");
                return true;
            }
            cordova.getActivity().runOnUiThread(
                    () -> showVerification(url, turnstileAction, initialUri, callbackContext)
            );
            return true;
        }
        if ("cancel".equals(action)) {
            cordova.getActivity().runOnUiThread(() -> finishError("cancel"));
            callbackContext.success();
            return true;
        }
        if ("showBadge".equals(action)) {
            JSONObject options = args.optJSONObject(0);
            String label = options == null
                    ? "Protected by Turnstile"
                    : options.optString("label", "Protected by Turnstile");
            String detail = options == null
                    ? "Privacy · Help"
                    : options.optString("detail", "Privacy · Help");
            String position = options == null
                    ? "bottomright"
                    : options.optString("position", "bottomright");
            if (label.length() > 80 || detail.length() > 80
                    || !("bottomright".equals(position) || "bottomleft".equals(position))) {
                callbackContext.error("invalid_options");
                return true;
            }
            cordova.getActivity().runOnUiThread(() -> {
                showBadge(label, detail, position);
                callbackContext.success();
            });
            return true;
        }
        if ("hideBadge".equals(action)) {
            cordova.getActivity().runOnUiThread(() -> {
                hideBadge();
                callbackContext.success();
            });
            return true;
        }
        return false;
    }

    private void showBadge(String label, String detail, String position) {
        Activity activity = cordova.getActivity();
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            return;
        }
        hideBadge();

        TurnstileBadgeView badge = new TurnstileBadgeView(activity, label, detail);
        badgeView = badge;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(activity, BADGE_COMPACT_WIDTH_DP),
                dp(activity, BADGE_HEIGHT_DP)
        );
        params.gravity = Gravity.BOTTOM
                | ("bottomleft".equals(position) ? Gravity.START : Gravity.END);
        params.bottomMargin = dp(activity, 14);
        ((ViewGroup) content).addView(badge, params);
    }

    private void hideBadge() {
        TurnstileBadgeView current = badgeView;
        badgeView = null;
        if (current != null) {
            current.dispose();
            if (current.getParent() instanceof ViewGroup) {
                ((ViewGroup) current.getParent()).removeView(current);
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showVerification(String url, String turnstileAction, Uri initialUri,
                                  CallbackContext callbackContext) {
        if (callback != null && !finished) {
            callbackContext.error("already_running");
            return;
        }

        Activity activity = cordova.getActivity();
        callback = callbackContext;
        expectedAction = turnstileAction;
        expectedHost = initialUri.getHost().toLowerCase(Locale.US);
        expectedPath = initialUri.getPath();
        finished = false;
        deadline = System.currentTimeMillis() + TIMEOUT_MS;

        WebView candidate = new WebView(activity);
        webView = candidate;
        candidate.setBackgroundColor(Color.rgb(244, 247, 248));

        WebSettings settings = candidate.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(candidate, true);
        }

        candidate.setWebChromeClient(new WebChromeClient());
        candidate.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String requestedUrl) {
                return !isAllowedWebViewUrl(requestedUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedWebViewUrl(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                if (isAllowedInitialUrl(loadedUrl)) {
                    schedulePoll();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (isAllowedInitialUrl(failingUrl)) {
                    finishError("load_error");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    finishError("load_error");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, android.net.http.SslError error) {
                sslHandler.cancel();
                finishError("ssl_error");
            }
        });

        Dialog candidateDialog = new Dialog(activity);
        dialog = candidateDialog;
        candidateDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        candidateDialog.setContentView(candidate, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        candidateDialog.setCanceledOnTouchOutside(false);
        candidateDialog.setOnCancelListener(ignored -> finishError("cancel"));
        candidateDialog.setOnDismissListener(ignored -> {
            if (!finished) {
                finishError("cancel");
            }
        });
        candidateDialog.show();

        Window window = candidateDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            int availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int availableHeight = activity.getResources().getDisplayMetrics().heightPixels;
            attributes.width = Math.min(
                    Math.round(availableWidth * 0.90f),
                    dp(activity, 360)
            );
            attributes.height = Math.min(
                    Math.round(availableHeight * 0.52f),
                    dp(activity, 320)
            );
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
        }

        candidate.loadUrl(url);
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS);
    }

    private final Runnable timeoutRunnable = () -> {
        if (!finished && System.currentTimeMillis() >= deadline) {
            finishError("timeout");
        }
    };

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            WebView current = webView;
            if (finished || current == null) {
                return;
            }
            current.evaluateJavascript(
                    "(function(){var r=window.cordovaTurnstileResult||window.argimTurnstileResult;"
                            + "if(!r){return '';}window.cordovaTurnstileResult=null;"
                            + "window.argimTurnstileResult=null;return JSON.stringify(r);}())",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            consumeResult(value);
                            if (!finished) {
                                handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                            }
                        }
                    }
            );
        }
    };

    private void consumeResult(String evaluatedValue) {
        if (evaluatedValue == null || "null".equals(evaluatedValue) || "\"\"".equals(evaluatedValue)) {
            return;
        }
        try {
            Object decoded = new JSONTokener(evaluatedValue).nextValue();
            if (!(decoded instanceof String)) {
                return;
            }
            JSONObject payload = new JSONObject((String) decoded);
            String type = payload.optString("type");
            if (!("turnstile".equals(type) || "argim-turnstile".equals(type))
                    || !expectedAction.equals(payload.optString("action"))) {
                return;
            }
            String status = payload.optString("status", "error");
            if ("success".equals(status)) {
                String token = payload.optString("token", "");
                if (token.length() >= 20 && token.length() <= 2048) {
                    finishSuccess(token);
                } else {
                    finishError("invalid_token");
                }
            } else if ("cancel".equals(status)) {
                finishError("cancel");
            } else if ("expired".equals(status)) {
                finishError("expired");
            } else if ("timeout".equals(status)) {
                finishError("timeout");
            } else {
                finishError("challenge_error");
            }
        } catch (Exception ignored) {
            finishError("invalid_response");
        }
    }

    private void finishSuccess(String token) {
        if (finished) {
            return;
        }
        finished = true;
        CallbackContext resultCallback = callback;
        cleanup();
        if (resultCallback != null) {
            resultCallback.success(token);
        }
    }

    private void finishError(String error) {
        if (finished || callback == null) {
            return;
        }
        finished = true;
        CallbackContext resultCallback = callback;
        cleanup();
        resultCallback.error(error);
    }

    private void cleanup() {
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(timeoutRunnable);

        Dialog currentDialog = dialog;
        dialog = null;
        if (currentDialog != null) {
            currentDialog.setOnCancelListener(null);
            currentDialog.setOnDismissListener(null);
            if (currentDialog.isShowing()) {
                currentDialog.dismiss();
            }
        }

        WebView currentWebView = webView;
        webView = null;
        if (currentWebView != null) {
            currentWebView.stopLoading();
            currentWebView.loadUrl("about:blank");
            currentWebView.clearHistory();
            currentWebView.removeAllViews();
            currentWebView.destroy();
        }
        callback = null;
        expectedAction = null;
        expectedHost = null;
        expectedPath = null;
    }

    private static boolean isAllowedAction(String action) {
        return action != null && action.matches("^[A-Za-z0-9_-]{1,32}$");
    }

    private static Uri parseInitialUrl(String value, String action) {
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().trim().isEmpty() || uri.getPath() == null
                    || uri.getPath().isEmpty() || !isAllowedAction(action)
                    || !action.equals(uri.getQueryParameter("action"))) {
                return null;
            }
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isAllowedInitialUrl(String value) {
        Uri uri = parseInitialUrl(value, expectedAction);
        return uri != null
                && expectedHost != null
                && expectedHost.equals(uri.getHost().toLowerCase(Locale.US))
                && expectedPath != null
                && expectedPath.equals(uri.getPath());
    }

    private boolean isAllowedWebViewUrl(String value) {
        if (value == null || value.startsWith("about:blank") || value.startsWith("about:srcdoc")) {
            return true;
        }
        try {
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            return (expectedHost != null && expectedHost.equals(host))
                    || "challenges.cloudflare.com".equals(host);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onReset() {
        cordova.getActivity().runOnUiThread(() -> {
            hideBadge();
            finishError("cancel");
        });
    }

    @Override
    public void onDestroy() {
        cordova.getActivity().runOnUiThread(() -> {
            hideBadge();
            finishError("cancel");
        });
        super.onDestroy();
    }

    private static final class TurnstileBadgeView extends View {
        private static final int ORANGE = Color.rgb(246, 130, 31);
        private static final int TEXT = Color.rgb(55, 71, 79);
        private static final int MUTED = Color.rgb(96, 125, 139);
        private static final int BORDER = Color.rgb(218, 223, 225);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler collapseHandler = new Handler(Looper.getMainLooper());
        private final String label;
        private final String detail;
        private final Drawable cloudflareLogo;
        private boolean expanded;
        private ValueAnimator widthAnimator;

        TurnstileBadgeView(Context context, String label, String detail) {
            super(context);
            this.label = label;
            this.detail = detail;
            int logoResource = getResources().getIdentifier(
                    "turnstile_cloudflare", "drawable", context.getPackageName());
            cloudflareLogo = logoResource == 0 ? null : context.getDrawable(logoResource);
            setClickable(true);
            setFocusable(true);
            setContentDescription(label + ". " + detail);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setElevation(dp(context, 5));
            }
            setOnClickListener(view -> toggle());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float width = getWidth();
            float height = getHeight();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(new RectF(0, 0, width, height), 4 * density, 4 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, density));
            paint.setColor(BORDER);
            canvas.drawRoundRect(new RectF(0.5f, 0.5f, width - 0.5f, height - 0.5f),
                    4 * density, 4 * density, paint);

            if (cloudflareLogo != null) {
                int logoLeft = Math.round((expanded ? -41 : -44) * density);
                int logoTop = Math.round((expanded ? 15 : 6) * density);
                int logoWidth = Math.round((expanded ? 98 : 109) * density);
                int logoHeight = Math.round((expanded ? 36 : 41) * density);
                cloudflareLogo.setBounds(logoLeft, logoTop,
                        logoLeft + logoWidth, logoTop + logoHeight);
                cloudflareLogo.draw(canvas);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            if (expanded) {
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setTextSize(13 * density);
                paint.setColor(TEXT);
                canvas.drawText(label, 59 * density, 25 * density, paint);
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
                paint.setTextSize(10 * density);
                paint.setColor(MUTED);
                canvas.drawText(detail, 59 * density, 43 * density, paint);
            } else {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(7 * density);
                paint.setColor(MUTED);
                canvas.drawText("TURNSTILE", width / 2f, 52 * density, paint);
            }
        }

        private void toggle() {
            setExpanded(!expanded);
            if (expanded) {
                collapseHandler.removeCallbacks(collapseRunnable);
                collapseHandler.postDelayed(collapseRunnable, 4500L);
            }
        }

        private final Runnable collapseRunnable = () -> setExpanded(false);

        private void setExpanded(boolean value) {
            if (expanded == value) {
                return;
            }
            expanded = value;
            int target = dp(getContext(), value ? BADGE_EXPANDED_WIDTH_DP : BADGE_COMPACT_WIDTH_DP);
            int start = getWidth() > 0 ? getWidth() : dp(getContext(), BADGE_COMPACT_WIDTH_DP);
            if (widthAnimator != null) {
                widthAnimator.cancel();
            }
            widthAnimator = ValueAnimator.ofInt(start, target);
            widthAnimator.setDuration(280L);
            widthAnimator.addUpdateListener(animation -> {
                ViewGroup.LayoutParams params = getLayoutParams();
                if (params != null) {
                    params.width = (Integer) animation.getAnimatedValue();
                    setLayoutParams(params);
                }
                invalidate();
            });
            widthAnimator.start();
        }

        void dispose() {
            collapseHandler.removeCallbacks(collapseRunnable);
            if (widthAnimator != null) {
                widthAnimator.cancel();
                widthAnimator = null;
            }
            setOnClickListener(null);
        }
    }
}
