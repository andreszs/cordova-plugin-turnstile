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

Do not accept the expected hostname or action from the app. They are security policy and must be configured by the backend.

For Linux and macOS shells:

```shell
export TURNSTILE_SECRET_KEY='YOUR_TURNSTILE_SECRET_KEY'
export TURNSTILE_EXPECTED_HOSTNAME='verify.example.com'
export TURNSTILE_EXPECTED_ACTION='register'
```

For PowerShell:

```powershell
$env:TURNSTILE_SECRET_KEY = 'YOUR_TURNSTILE_SECRET_KEY'
$env:TURNSTILE_EXPECTED_HOSTNAME = 'verify.example.com'
$env:TURNSTILE_EXPECTED_ACTION = 'register'
```

## PHP

[`php/validate-turnstile.php`](php/validate-turnstile.php) is a dependency-free PHP endpoint. It requires the cURL extension.

For local development, after setting the environment variables:

```shell
php -S 127.0.0.1:8080 -t php
```

Send protected requests to `http://127.0.0.1:8080/validate-turnstile.php`. In production, run it through a maintained PHP runtime and web server over HTTPS.

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

## Production checklist

- Use HTTPS and keep the secret key in a secret manager or protected environment configuration.
- Add the authentication, authorization, input validation, rate limiting, logging, and body limits required by the protected operation.
- Configure CORS explicitly if the endpoint must accept browser origins; do not use a wildcard by default.
- Configure trusted proxies before deriving a client IP, or omit the optional `remoteip` Siteverify field as these examples do.
- Do not log tokens or return Cloudflare's detailed verification response to clients.
- Create a separate expected action configuration or endpoint for each protected operation.
- Remember that Turnstile tokens expire after five minutes and can be validated only once.
