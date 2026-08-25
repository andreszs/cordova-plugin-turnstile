<?php
declare(strict_types=1);

const SITEVERIFY_URL = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
const MAX_REQUEST_BYTES = 16384;

function jsonResponse(int $status, array $body): void
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($body, JSON_UNESCAPED_SLASHES);
    exit;
}

function requiredEnvironmentVariable(string $name): string
{
    $value = getenv($name);

    if (!is_string($value) || trim($value) === '') {
        jsonResponse(500, ['ok' => false, 'message' => 'Server configuration is incomplete.']);
    }

    return trim($value);
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    header('Allow: POST');
    jsonResponse(405, ['ok' => false, 'message' => 'Method not allowed.']);
}

$contentType = $_SERVER['CONTENT_TYPE'] ?? '';
if (stripos($contentType, 'application/json') !== 0) {
    jsonResponse(415, ['ok' => false, 'message' => 'Content-Type must be application/json.']);
}

$rawBody = file_get_contents('php://input');
if ($rawBody === false || strlen($rawBody) > MAX_REQUEST_BYTES) {
    jsonResponse(413, ['ok' => false, 'message' => 'Request body is too large.']);
}

try {
    $request = json_decode($rawBody, true, 16, JSON_THROW_ON_ERROR);
} catch (JsonException $exception) {
    jsonResponse(400, ['ok' => false, 'message' => 'Request body must contain valid JSON.']);
}

if (!is_array($request)) {
    jsonResponse(400, ['ok' => false, 'message' => 'Request body must be a JSON object.']);
}

$token = $request['turnstileToken'] ?? null;
if (!is_string($token) || strlen($token) < 20 || strlen($token) > 2048) {
    jsonResponse(400, ['ok' => false, 'message' => 'A valid turnstileToken is required.']);
}

$secretKey = requiredEnvironmentVariable('TURNSTILE_SECRET_KEY');
$expectedHostname = requiredEnvironmentVariable('TURNSTILE_EXPECTED_HOSTNAME');
$expectedAction = requiredEnvironmentVariable('TURNSTILE_EXPECTED_ACTION');

if (!preg_match('/^[A-Za-z0-9_-]{1,32}$/', $expectedAction)) {
    jsonResponse(500, ['ok' => false, 'message' => 'Server configuration is invalid.']);
}

$curl = curl_init(SITEVERIFY_URL);
if ($curl === false) {
    jsonResponse(502, ['ok' => false, 'message' => 'Turnstile verification is unavailable.']);
}

curl_setopt_array($curl, [
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => http_build_query([
        'secret' => $secretKey,
        'response' => $token,
    ]),
    CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CONNECTTIMEOUT => 5,
    CURLOPT_TIMEOUT => 10,
]);

$responseBody = curl_exec($curl);
$responseStatus = (int) curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
$curlError = curl_errno($curl);
curl_close($curl);

if ($responseBody === false || $curlError !== 0 || $responseStatus < 200 || $responseStatus >= 300) {
    jsonResponse(502, ['ok' => false, 'message' => 'Turnstile verification is unavailable.']);
}

try {
    $verification = json_decode($responseBody, true, 16, JSON_THROW_ON_ERROR);
} catch (JsonException $exception) {
    jsonResponse(502, ['ok' => false, 'message' => 'Turnstile verification is unavailable.']);
}

$isValid = is_array($verification)
    && ($verification['success'] ?? false) === true
    && ($verification['hostname'] ?? null) === $expectedHostname
    && ($verification['action'] ?? null) === $expectedAction;

if (!$isValid) {
    jsonResponse(403, ['ok' => false, 'message' => 'Turnstile verification failed.']);
}

// Perform the protected operation here, using $request['payload'] only after verification.
jsonResponse(200, ['ok' => true, 'message' => 'Protected request accepted.']);
