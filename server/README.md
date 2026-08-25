# Server examples

This directory contains two separate server-side pieces:

- [`turnstile.php`](turnstile.php) is the HTTPS challenge page displayed by the plugin. It contains the public site key and renders the Turnstile widget.
- The `php`, `node`, and `python` directories contain equivalent backend endpoints. They keep the secret key private, validate the token with Cloudflare Siteverify, and only then execute the protected operation.

The challenge page is not the backend validation endpoint. Hosting the page and receiving a token does not replace server-side validation.

## Common request contract

After `cordova.plugins.turnstile.verify()` returns a token, the app should immediately send it to the backend together with the data for the protected operation:

```http
POST /api/protected-action HTTP/1.1
Content-Type: application/json

{
  "turnstileToken": "TOKEN_RETURNED_BY_THE_PLUGIN",
  "payload": {
    "exampleField": "value"
  }
}
```

Each example reads these values from server environment variables:

| Variable | Purpose | Example |
| --- | --- | --- |
| `TURNSTILE_SECRET_KEY` | Private secret from the Turnstile dashboard | `0x4AAAA...` |
| `TURNSTILE_EXPECTED_HOSTNAME` | Exact hostname that serves the challenge page | `verify.example.com` |
| `TURNSTILE_EXPECTED_ACTION` | Exact action for this protected endpoint | `register` |
| `CORDOVA_ALLOWED_ORIGIN` | Exact origin of the Cordova main WebView | `https://localhost` |

Do not accept the expected hostname, action, or allowed origin from the app. They are security policy and must be configured by the backend.

cordova-android uses `https://localhost` by default. If the app changes its Cordova `scheme` or `hostname` preference, set `CORDOVA_ALLOWED_ORIGIN` to the resulting exact origin without a trailing slash. The examples return an explicit CORS policy, reject other supplied origins, and answer preflight `OPTIONS` requests without running Siteverify or the protected operation.

The examples are complete Turnstile validators, but they cannot know your application's protected operation. You must either replace the marked `Perform the protected operation here` line with that operation or copy the validation code into your existing backend handler. Do not use this as a validation-only service followed by a separate unprotected request.

## Where to define the environment variables

Define the variables in the environment of the process that runs the backend. Do not paste the secret key into these example files, the Cordova application, or the hosted challenge page.

Use whichever option matches your server:

- **Hosting platform:** use its environment-variable or secret-management panel.
- **Apache or XAMPP:** add the following directives inside the site's `VirtualHost`. On XAMPP for Windows, virtual hosts are normally configured in `C:\xampp\apache\conf\extra\httpd-vhosts.conf`. Restart Apache after changing it. Apache documents `SetEnv` in [`mod_env`](https://httpd.apache.org/docs/current/mod/mod_env.html#setenv).

  ```apache
  SetEnv TURNSTILE_SECRET_KEY "YOUR_TURNSTILE_SECRET_KEY"
  SetEnv TURNSTILE_EXPECTED_HOSTNAME "verify.example.com"
  SetEnv TURNSTILE_EXPECTED_ACTION "register"
  SetEnv CORDOVA_ALLOWED_ORIGIN "https://localhost"
  ```

- **PHP-FPM:** add the values to the relevant pool configuration and restart PHP-FPM. See the official [PHP-FPM configuration documentation](https://www.php.net/manual/en/install.fpm.configuration.php).

  ```ini
  env[TURNSTILE_SECRET_KEY] = YOUR_TURNSTILE_SECRET_KEY
  env[TURNSTILE_EXPECTED_HOSTNAME] = verify.example.com
  env[TURNSTILE_EXPECTED_ACTION] = register
  env[CORDOVA_ALLOWED_ORIGIN] = https://localhost
  ```

- **Shell-launched development server:** set the variables in the same terminal before starting PHP, Node.js, or Uvicorn. The values apply only to processes started from that terminal.

For Linux and macOS shells:

```shell
export TURNSTILE_SECRET_KEY='YOUR_TURNSTILE_SECRET_KEY'
export TURNSTILE_EXPECTED_HOSTNAME='verify.example.com'
export TURNSTILE_EXPECTED_ACTION='register'
export CORDOVA_ALLOWED_ORIGIN='https://localhost'
```

For PowerShell:

```powershell
$env:TURNSTILE_SECRET_KEY = 'YOUR_TURNSTILE_SECRET_KEY'
$env:TURNSTILE_EXPECTED_HOSTNAME = 'verify.example.com'
$env:TURNSTILE_EXPECTED_ACTION = 'register'
$env:CORDOVA_ALLOWED_ORIGIN = 'https://localhost'
```

## PHP

[`php/validate-turnstile.php`](php/validate-turnstile.php) is a dependency-free endpoint compatible with PHP 5.3 or later. It requires the cURL and JSON extensions.

For local development with PHP 5.4 or later, after setting the variables in the same shell:

```shell
php -S 127.0.0.1:8080 -t php
```

PHP 5.3 does not include the built-in development server. Use Apache/XAMPP and define the variables in its `VirtualHost` as shown above. Send protected requests to the URL that maps to `validate-turnstile.php`. Syntax compatibility does not make obsolete PHP releases safe: in production, use a currently maintained PHP runtime and web server over HTTPS.

## Node.js

[`node/server.js`](node/server.js) uses only APIs built into Node.js 22 or later, so there are no packages to install.

```shell
cd node
npm start
```

Send protected requests to `http://127.0.0.1:3000/api/protected-action`. Set `HOST` or `PORT` to change the listener.

## Python with FastAPI

[`python/app.py`](python/app.py) provides the same endpoint using FastAPI and HTTPX. It requires Python 3.11 or later.

```shell
cd python
python -m venv .venv
python -m pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000
```

Send protected requests to `http://127.0.0.1:8000/api/protected-action`.

## What every example verifies

Each endpoint:

1. Accepts only a small JSON `POST` request with a plausible token.
2. Sends the token and server-only secret key to Cloudflare's [Siteverify endpoint](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/).
3. Fails closed if Siteverify is unavailable or returns an invalid response.
4. Requires `success: true` and exact matches for the configured `hostname` and `action`.
5. Reaches the placeholder for the protected operation only after all checks pass.

Replace that placeholder with the real operation, such as account creation, login, form submission, or password recovery. Preserve the order: never change protected state before validation succeeds.

The examples fail closed on a Siteverify transport failure and do not retry automatically. If production code adds retries, generate one UUID for the logical validation and send that same value as Cloudflare's optional `idempotency_key` on every retry.

## Production checklist

- Use HTTPS and keep the secret key in a secret manager or protected environment configuration.
- Add the authentication, authorization, input validation, rate limiting, logging, and body limits required by the protected operation.
- Keep `CORDOVA_ALLOWED_ORIGIN` restricted to the exact Cordova origin; do not replace it with a wildcard.
- Configure trusted proxies before deriving a client IP, or omit the optional `remoteip` Siteverify field as these examples do.
- Do not log tokens or return Cloudflare's detailed verification response to clients.
- Create a separate expected action configuration or endpoint for each protected operation.
- Remember that Turnstile tokens expire after five minutes and can be validated only once.
