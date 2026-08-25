<?php
declare(strict_types=1);

/*
 * Self-hosted challenge page for cordova-plugin-turnstile.
 *
 * 1. Replace the value below with your public Cloudflare Turnstile site key.
 * 2. Upload this file to an HTTPS server allowed by your Turnstile widget.
 * 3. Open it through the plugin with a matching action query parameter:
 *    https://example.com/turnstile.php?action=register
 *
 * Never place your Turnstile secret key in this file.
 */
$turnstileSiteKey = 'YOUR_TURNSTILE_SITE_KEY';

// The URL action must exactly match the action passed to plugin.verify().
$action = isset($_GET['action']) && is_string($_GET['action'])
    ? $_GET['action']
    : '';

if (!preg_match('/^[A-Za-z0-9_-]{1,32}$/', $action)) {
    http_response_code(400);
    exit('Invalid action.');
}

if ($turnstileSiteKey === 'YOUR_TURNSTILE_SITE_KEY'
    || !preg_match('/^0x[A-Za-z0-9_-]{20,62}$/', $turnstileSiteKey)) {
    http_response_code(503);
    exit('Configure a valid Cloudflare Turnstile site key before using this page.');
}

// Restrict this page to the resources required by the Turnstile widget.
$nonce = rtrim(strtr(base64_encode(random_bytes(18)), '+/', '-_'), '=');
$csp = "default-src 'none'; "
    ."script-src 'nonce-$nonce' 'strict-dynamic' https://challenges.cloudflare.com; "
    ."frame-src https://challenges.cloudflare.com; "
    ."connect-src https://challenges.cloudflare.com; "
    ."style-src 'nonce-$nonce'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

header('Content-Type: text/html; charset=UTF-8');
header('Content-Security-Policy: '.$csp);
header('Referrer-Policy: no-referrer');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, max-age=0');
header('Pragma: no-cache');
?>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
    <title>Security verification</title>
    <style nonce="<?=htmlspecialchars($nonce, ENT_QUOTES, 'UTF-8')?>">
        *{box-sizing:border-box}
        html,body{background:#f4f7f8;color:#263238;font-family:Arial,sans-serif;height:100%;margin:0}
        main{align-items:center;display:flex;flex-direction:column;justify-content:center;min-height:100%;padding:16px;text-align:center}
        h1{color:#00695c;font-size:19px;margin:0 0 6px}
        p{font-size:13px;line-height:1.35;margin:0 0 12px;max-width:320px}
        #turnstile-widget{max-width:320px;min-height:65px;width:100%}
        #status{color:#607d8b;font-size:12px;margin-top:8px;min-height:16px}
        button{background:#fff;border:1px solid #90a4ae;border-radius:4px;color:#37474f;font-size:14px;margin-top:12px;padding:8px 20px}
    </style>
    <script nonce="<?=htmlspecialchars($nonce, ENT_QUOTES, 'UTF-8')?>">
        'use strict';
        var cordovaTurnstileAction = <?=json_encode($action, JSON_UNESCAPED_SLASHES)?>;
        var cordovaTurnstileSiteKey = <?=json_encode($turnstileSiteKey, JSON_UNESCAPED_SLASHES)?>;

        function sendResult(payload) {
            // The Android plugin polls this variable and consumes it once.
            window.cordovaTurnstileResult = payload;
        }

        function onTurnstileLoad() {
            turnstile.render('#turnstile-widget', {
                sitekey: cordovaTurnstileSiteKey,
                action: cordovaTurnstileAction,
                appearance: 'always',
                execution: 'render',
                size: 'flexible',
                theme: 'light',
                language: 'auto',
                callback: function (token) {
                    document.getElementById('status').textContent = 'Verification completed.';
                    sendResult({
                        type: 'turnstile',
                        status: 'success',
                        action: cordovaTurnstileAction,
                        token: token
                    });
                },
                'error-callback': function (code) {
                    document.getElementById('status').textContent = 'Cloudflare error: ' + String(code || 'unknown');
                    sendResult({
                        type: 'turnstile',
                        status: 'error',
                        action: cordovaTurnstileAction,
                        code: String(code || '')
                    });
                },
                'expired-callback': function () {
                    sendResult({
                        type: 'turnstile',
                        status: 'expired',
                        action: cordovaTurnstileAction
                    });
                },
                'timeout-callback': function () {
                    sendResult({
                        type: 'turnstile',
                        status: 'timeout',
                        action: cordovaTurnstileAction
                    });
                }
            });
        }

        document.addEventListener('DOMContentLoaded', function () {
            document.getElementById('cancel-button').addEventListener('click', function () {
                sendResult({
                    type: 'turnstile',
                    status: 'cancel',
                    action: cordovaTurnstileAction
                });
            });
        });
    </script>
    <script nonce="<?=htmlspecialchars($nonce, ENT_QUOTES, 'UTF-8')?>" src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&amp;onload=onTurnstileLoad" defer></script>
</head>
<body>
<main>
    <h1>Security verification</h1>
    <p>Complete the check below to continue.</p>
    <div id="turnstile-widget"></div>
    <div id="status">Starting verification…</div>
    <button type="button" id="cancel-button">Cancel</button>
</main>
</body>
</html>
