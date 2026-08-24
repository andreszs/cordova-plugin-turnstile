# cordova-plugin-turnstile

A Cordova plugin that runs [Cloudflare Turnstile](https://developers.cloudflare.com/turnstile/) in a secure Android WebView dialog without navigating away from the Cordova screen that requested verification.

The host application provides an HTTPS page that renders the Turnstile widget. During verification, the plugin restricts WebView navigation to that page's host and `challenges.cloudflare.com`, rejects SSL errors and mixed content, and returns only the ephemeral Turnstile token to JavaScript.

## Platform support

- Android
- Cordova 11 or later
- cordova-android 11 or later

## Installation

Install directly from GitHub:

```shell
cordova plugin add https://github.com/andreszs/cordova-plugin-turnstile.git
```

When the package is available from npm, it can also be installed with:

```shell
cordova plugin add cordova-plugin-turnstile
```

## Usage

Call `verify` with the URL of your HTTPS Turnstile page and the expected action. The URL must contain an `action` query parameter whose value exactly matches the `action` option.

```javascript
cordova.plugins.turnstile.verify(
    {
        url: 'https://example.com/turnstile-mobile?action=register',
        action: 'register'
    },
    function (token) {
        // Send the token to your backend immediately for verification.
    },
    function (errorCode) {
        console.error('Turnstile verification failed:', errorCode);
    }
);
```

Only letters, numbers, underscores, and hyphens are accepted in the action, with a maximum length of 32 characters.

### Remote page contract

The HTTPS page that renders Turnstile must publish the challenge result through `window.cordovaTurnstileResult`. A successful result has the following shape:

```javascript
window.cordovaTurnstileResult = {
    type: 'turnstile',
    status: 'success',
    action: 'register',
    token: token
};
```

The `action` value must match the action passed to `verify`. Supported non-success statuses include `cancel`, `expired`, and `timeout`; any other status is reported as `challenge_error`.

### Cancel an active verification

```javascript
cordova.plugins.turnstile.cancel(
    function () {
        console.log('Verification cancelled.');
    },
    function (errorCode) {
        console.error(errorCode);
    }
);
```

### Optional badge

The plugin can display a compact, expandable informational badge over the Cordova activity:

```javascript
cordova.plugins.turnstile.showBadge({
    label: 'Protected by Turnstile',
    detail: 'Privacy · Help',
    position: 'bottomright'
});

cordova.plugins.turnstile.hideBadge();
```

`position` accepts `bottomright` or `bottomleft`. The label and detail are each limited to 80 characters.

## Error codes

The error callback may receive one of these string codes:

| Code | Meaning |
| --- | --- |
| `already_running` | Another verification is already active. |
| `cancel` | The user or application cancelled verification. |
| `challenge_error` | The remote page reported an unsuccessful challenge. |
| `expired` | The challenge or token expired. |
| `invalid_options` | The URL or action is missing or invalid. |
| `invalid_response` | The remote page returned an unreadable result. |
| `invalid_token` | The returned token did not pass basic length validation. |
| `load_error` | The verification page failed to load. |
| `ssl_error` | Android rejected the page's TLS certificate. |
| `timeout` | Verification did not complete within 120 seconds. |

## Server-side validation

A token returned by this plugin is not proof of successful verification by itself. Send it to your backend immediately and validate it with Cloudflare's Siteverify API. Keep the Turnstile secret key on the server; never embed it in the Cordova application or the remote challenge page.

The backend should also verify the expected hostname and action in the Siteverify response before accepting the request. Turnstile tokens are single-use and expire after a short period.

See the [Cloudflare server-side validation documentation](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/) for the required request and response handling.

## Security notes

- Only HTTPS verification URLs are accepted.
- File and content access are disabled in the verification WebView.
- Mixed HTTP/HTTPS content is rejected.
- SSL errors are never bypassed.
- Navigation is restricted to the initial page's host and `challenges.cloudflare.com`.
- Only one verification can run at a time.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

Cloudflare, the Cloudflare logo, and Cloudflare Turnstile are trademarks of Cloudflare, Inc. This project is not affiliated with or endorsed by Cloudflare, Inc.
