<?php
/**
 * Backend validation example for cordova-plugin-turnstile.
 * Receives a Turnstile token, validates it with Cloudflare Siteverify, and only
 * then runs the protected operation. Define TURNSTILE_SECRET_KEY,
 * TURNSTILE_EXPECTED_HOSTNAME, TURNSTILE_EXPECTED_ACTION, and
 * CORDOVA_ALLOWED_ORIGIN outside this file in the hosting panel, Apache
 * VirtualHost, or PHP-FPM pool configuration. Edit only the marked
 * protected-operation line to add your application logic.
 * Requires PHP 5.3+ with the cURL and JSON extensions enabled.
 * Plugin: https://github.com/andreszs/cordova-plugin-turnstile
 */

define('SITEVERIFY_URL', 'https://challenges.cloudflare.com/turnstile/v0/siteverify');
define('MAX_REQUEST_BYTES', 16384);

function jsonResponse($status, $body)
{
    $statusTexts = array(
        200 => 'OK',
        400 => 'Bad Request',
        403 => 'Forbidden',
        405 => 'Method Not Allowed',
        413 => 'Request Entity Too Large',
        415 => 'Unsupported Media Type',
        500 => 'Internal Server Error',
        502 => 'Bad Gateway'
    );
    $statusText = isset($statusTexts[$status]) ? $statusTexts[$status] : 'Unknown Status';

    header('HTTP/1.1 ' . $status . ' ' . $statusText);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($body);
    exit;
}

function requiredEnvironmentVariable($name)
{
    $value = getenv($name);

    if (!is_string($value) || trim($value) === '') {
        jsonResponse(500, array('ok' => false, 'message' => 'Server configuration is incomplete.'));
    }

    return trim($value);
}

if (!function_exists('json_encode') || !function_exists('json_decode') || !function_exists('curl_init')) {
    header('HTTP/1.1 500 Internal Server Error');
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo '{"ok":false,"message":"The PHP cURL and JSON extensions are required."}';
    exit;
}

$allowedOrigin = requiredEnvironmentVariable('CORDOVA_ALLOWED_ORIGIN');
if (!preg_match('#^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?$#', $allowedOrigin)) {
    jsonResponse(500, array('ok' => false, 'message' => 'Server configuration is invalid.'));
}

header('Access-Control-Allow-Origin: ' . $allowedOrigin);
header('Vary: Origin');
header('Access-Control-Allow-Methods: POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');
header('Access-Control-Max-Age: 600');

$requestOrigin = isset($_SERVER['HTTP_ORIGIN']) ? $_SERVER['HTTP_ORIGIN'] : '';
if ($requestOrigin !== '' && $requestOrigin !== $allowedOrigin) {
    jsonResponse(403, array('ok' => false, 'message' => 'Origin not allowed.'));
}

$requestMethod = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : '';
if ($requestMethod === 'OPTIONS') {
    header('HTTP/1.1 204 No Content');
    header('Cache-Control: no-store');
    exit;
}

if ($requestMethod !== 'POST') {
    header('Allow: POST, OPTIONS');
    jsonResponse(405, array('ok' => false, 'message' => 'Method not allowed.'));
}

$contentType = isset($_SERVER['CONTENT_TYPE']) ? $_SERVER['CONTENT_TYPE'] : '';
if (stripos($contentType, 'application/json') !== 0) {
    jsonResponse(415, array('ok' => false, 'message' => 'Content-Type must be application/json.'));
}

$rawBody = file_get_contents('php://input');
if ($rawBody === false || strlen($rawBody) > MAX_REQUEST_BYTES) {
    jsonResponse(413, array('ok' => false, 'message' => 'Request body is too large.'));
}

$request = json_decode($rawBody, true);
if (json_last_error() !== JSON_ERROR_NONE || !is_array($request)) {
    jsonResponse(400, array('ok' => false, 'message' => 'Request body must contain a valid JSON object.'));
}

$token = isset($request['turnstileToken']) ? $request['turnstileToken'] : null;
if (!is_string($token) || strlen($token) < 20 || strlen($token) > 2048) {
    jsonResponse(400, array('ok' => false, 'message' => 'A valid turnstileToken is required.'));
}

$secretKey = requiredEnvironmentVariable('TURNSTILE_SECRET_KEY');
$expectedHostname = requiredEnvironmentVariable('TURNSTILE_EXPECTED_HOSTNAME');
$expectedAction = requiredEnvironmentVariable('TURNSTILE_EXPECTED_ACTION');

if (!preg_match('/^[A-Za-z0-9_-]{1,32}$/', $expectedAction)) {
    jsonResponse(500, array('ok' => false, 'message' => 'Server configuration is invalid.'));
}

$curl = curl_init(SITEVERIFY_URL);
if ($curl === false) {
    jsonResponse(502, array('ok' => false, 'message' => 'Turnstile verification is unavailable.'));
}

curl_setopt_array($curl, array(
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => http_build_query(array(
        'secret' => $secretKey,
        'response' => $token
    )),
    CURLOPT_HTTPHEADER => array('Content-Type: application/x-www-form-urlencoded'),
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CONNECTTIMEOUT => 5,
    CURLOPT_TIMEOUT => 10
));

$responseBody = curl_exec($curl);
$responseStatus = (int) curl_getinfo($curl, CURLINFO_HTTP_CODE);
$curlError = curl_errno($curl);
curl_close($curl);

if ($responseBody === false || $curlError !== 0 || $responseStatus < 200 || $responseStatus >= 300) {
    jsonResponse(502, array('ok' => false, 'message' => 'Turnstile verification is unavailable.'));
}

$verification = json_decode($responseBody, true);
$isValid = json_last_error() === JSON_ERROR_NONE
    && is_array($verification)
    && isset($verification['success']) && $verification['success'] === true
    && isset($verification['hostname']) && $verification['hostname'] === $expectedHostname
    && isset($verification['action']) && $verification['action'] === $expectedAction;

if (!$isValid) {
    jsonResponse(403, array('ok' => false, 'message' => 'Turnstile verification failed.'));
}

// Perform the protected operation here, using $request['payload'] only after verification.
jsonResponse(200, array('ok' => true, 'message' => 'Protected request accepted.'));
