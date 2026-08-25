"""Backend validation example for cordova-plugin-turnstile.

Receives a Turnstile token, validates it with Cloudflare Siteverify, and only
then runs the protected operation. Define TURNSTILE_SECRET_KEY,
TURNSTILE_EXPECTED_HOSTNAME, TURNSTILE_EXPECTED_ACTION, and
CORDOVA_ALLOWED_ORIGIN outside this file in the hosting platform or process
configuration. Edit only the marked protected-operation line to add your
application logic.
Plugin: https://github.com/andreszs/cordova-plugin-turnstile
"""

import os
import re
from typing import Any
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"


def required_environment_variable(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


SECRET_KEY = required_environment_variable("TURNSTILE_SECRET_KEY")
EXPECTED_HOSTNAME = required_environment_variable("TURNSTILE_EXPECTED_HOSTNAME")
EXPECTED_ACTION = required_environment_variable("TURNSTILE_EXPECTED_ACTION")
ALLOWED_ORIGIN = required_environment_variable("CORDOVA_ALLOWED_ORIGIN")

if re.fullmatch(r"[A-Za-z0-9_-]{1,32}", EXPECTED_ACTION) is None:
    raise RuntimeError("TURNSTILE_EXPECTED_ACTION must be a valid Turnstile action.")

parsed_origin = urlsplit(ALLOWED_ORIGIN)
if (
    parsed_origin.scheme not in {"http", "https"}
    or not parsed_origin.hostname
    or parsed_origin.path
    or parsed_origin.query
    or parsed_origin.fragment
    or parsed_origin.username
    or parsed_origin.password
):
    raise RuntimeError("CORDOVA_ALLOWED_ORIGIN must be an exact HTTP or HTTPS origin.")


class ProtectedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    turnstileToken: str = Field(min_length=20, max_length=2048)
    payload: dict[str, Any] = Field(default_factory=dict)


app = FastAPI(title="Turnstile backend example")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[ALLOWED_ORIGIN],
    allow_credentials=False,
    allow_methods=["POST"],
    allow_headers=["Content-Type"],
    max_age=600,
)


@app.post("/api/protected-action")
async def protected_action(request: ProtectedRequest) -> dict[str, Any]:
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                SITEVERIFY_URL,
                data={"secret": SECRET_KEY, "response": request.turnstileToken},
            )
            response.raise_for_status()
            verification = response.json()
    except (httpx.HTTPError, ValueError) as exception:
        raise HTTPException(status_code=502, detail="Turnstile verification is unavailable.") from exception

    is_valid = (
        isinstance(verification, dict)
        and verification.get("success") is True
        and verification.get("hostname") == EXPECTED_HOSTNAME
        and verification.get("action") == EXPECTED_ACTION
    )

    if not is_valid:
        raise HTTPException(status_code=403, detail="Turnstile verification failed.")

    # Perform the protected operation here, using request.payload only after verification.
    return {"ok": True, "message": "Protected request accepted."}
