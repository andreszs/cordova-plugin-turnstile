/**
 * Backend validation example for cordova-plugin-turnstile.
 * Receives a Turnstile token, validates it with Cloudflare Siteverify, and only
 * then runs the protected operation. No credentials should be added here: set
 * the three TURNSTILE_* environment variables and replace the marked operation.
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
    host: process.env.HOST?.trim() || '127.0.0.1',
    port: Number.parseInt(process.env.PORT || '3000', 10),
};

if (!/^[A-Za-z0-9_-]{1,32}$/.test(config.expectedAction)) {
    throw new Error('TURNSTILE_EXPECTED_ACTION must be a valid Turnstile action.');
}

function sendJson(response, status, body) {
    response.writeHead(status, {
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

    if (request.method !== 'POST' || url.pathname !== '/api/protected-action') {
        sendJson(response, 404, { ok: false, message: 'Not found.' });
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
