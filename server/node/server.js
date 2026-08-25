/**
 * Backend validation example for cordova-plugin-turnstile.
 * Receives a Turnstile token, validates it with Cloudflare Siteverify, and only
 * then runs the protected operation. Define TURNSTILE_SECRET_KEY,
 * TURNSTILE_EXPECTED_HOSTNAME, TURNSTILE_EXPECTED_ACTION, and
 * CORDOVA_ALLOWED_ORIGIN outside this file in the hosting platform or process
 * configuration. Edit only the marked protected-operation line to add your
 * application logic.
 * Plugin: https://github.com/andreszs/cordova-plugin-turnstile
 */
import { createServer } from 'node:http';

const SITEVERIFY_URL = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
const MAX_REQUEST_BYTES = 16 * 1024;

function requiredEnvironmentVariable(name) {
    const value = process.env[name]?.trim();

    if (!value) {
        throw new Error(`Missing required environment variable: ${name}`);
    }

    return value;
}

const config = {
    secretKey: requiredEnvironmentVariable('TURNSTILE_SECRET_KEY'),
    expectedHostname: requiredEnvironmentVariable('TURNSTILE_EXPECTED_HOSTNAME'),
    expectedAction: requiredEnvironmentVariable('TURNSTILE_EXPECTED_ACTION'),
    allowedOrigin: requiredEnvironmentVariable('CORDOVA_ALLOWED_ORIGIN'),
    host: process.env.HOST?.trim() || '127.0.0.1',
    port: Number.parseInt(process.env.PORT || '3000', 10),
};

if (!/^[A-Za-z0-9_-]{1,32}$/.test(config.expectedAction)) {
    throw new Error('TURNSTILE_EXPECTED_ACTION must be a valid Turnstile action.');
}

try {
    const allowedOrigin = new URL(config.allowedOrigin);
    if (!['http:', 'https:'].includes(allowedOrigin.protocol)
            || allowedOrigin.origin !== config.allowedOrigin
            || allowedOrigin.username || allowedOrigin.password) {
        throw new Error();
    }
} catch {
    throw new Error('CORDOVA_ALLOWED_ORIGIN must be an exact HTTP or HTTPS origin.');
}

function corsHeaders() {
    return {
        'Access-Control-Allow-Origin': config.allowedOrigin,
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Max-Age': '600',
        'Vary': 'Origin',
    };
}

function sendJson(response, status, body) {
    response.writeHead(status, {
        ...corsHeaders(),
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
    });
    response.end(JSON.stringify(body));
}

function readJson(request) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let size = 0;
        let tooLarge = false;

        request.on('data', (chunk) => {
            size += chunk.length;

            if (size > MAX_REQUEST_BYTES) {
                tooLarge = true;
                chunks.length = 0;
                return;
            }

            if (!tooLarge) {
                chunks.push(chunk);
            }
        });

        request.on('end', () => {
            if (tooLarge) {
                reject(Object.assign(new Error('Request body is too large.'), { statusCode: 413 }));
                return;
            }

            try {
                resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
            } catch {
                reject(Object.assign(new Error('Request body must contain valid JSON.'), { statusCode: 400 }));
            }
        });

        request.on('error', reject);
    });
}

async function verifyTurnstileToken(token) {
    const body = new URLSearchParams({
        secret: config.secretKey,
        response: token,
    });

    let response;

    try {
        response = await fetch(SITEVERIFY_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body,
            signal: AbortSignal.timeout(10_000),
        });
    } catch {
        throw Object.assign(new Error('Turnstile verification is unavailable.'), { statusCode: 502 });
    }

    if (!response.ok) {
        throw Object.assign(new Error('Turnstile verification is unavailable.'), { statusCode: 502 });
    }

    try {
        return await response.json();
    } catch {
        throw Object.assign(new Error('Turnstile verification is unavailable.'), { statusCode: 502 });
    }
}

const server = createServer(async (request, response) => {
    const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);

    if (url.pathname !== '/api/protected-action') {
        sendJson(response, 404, { ok: false, message: 'Not found.' });
        return;
    }

    const requestOrigin = request.headers.origin;
    if (requestOrigin && requestOrigin !== config.allowedOrigin) {
        sendJson(response, 403, { ok: false, message: 'Origin not allowed.' });
        return;
    }

    if (request.method === 'OPTIONS') {
        response.writeHead(204, corsHeaders());
        response.end();
        return;
    }

    if (request.method !== 'POST') {
        response.writeHead(405, {...corsHeaders(), Allow: 'POST, OPTIONS'});
        response.end();
        return;
    }

    if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) {
        sendJson(response, 415, { ok: false, message: 'Content-Type must be application/json.' });
        return;
    }

    try {
        const input = await readJson(request);
        const token = input?.turnstileToken;

        if (typeof input !== 'object' || input === null || Array.isArray(input)) {
            sendJson(response, 400, { ok: false, message: 'Request body must be a JSON object.' });
            return;
        }

        if (typeof token !== 'string' || token.length < 20 || token.length > 2048) {
            sendJson(response, 400, { ok: false, message: 'A valid turnstileToken is required.' });
            return;
        }

        const verification = await verifyTurnstileToken(token);
        const isValid = verification?.success === true
            && verification.hostname === config.expectedHostname
            && verification.action === config.expectedAction;

        if (!isValid) {
            sendJson(response, 403, { ok: false, message: 'Turnstile verification failed.' });
            return;
        }

        // Perform the protected operation here, using input.payload only after verification.
        sendJson(response, 200, { ok: true, message: 'Protected request accepted.' });
    } catch (error) {
        const status = Number.isInteger(error.statusCode) ? error.statusCode : 502;
        const message = status < 500 ? error.message : 'Turnstile verification is unavailable.';
        sendJson(response, status, { ok: false, message });
    }
});

server.listen(config.port, config.host, () => {
    console.log(`Turnstile backend example listening on http://${config.host}:${config.port}`);
});
