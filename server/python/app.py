import os
import re
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException
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

if re.fullmatch(r"[A-Za-z0-9_-]{1,32}", EXPECTED_ACTION) is None:
    raise RuntimeError("TURNSTILE_EXPECTED_ACTION must be a valid Turnstile action.")


class ProtectedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    turnstileToken: str = Field(min_length=20, max_length=2048)
    payload: dict[str, Any] = Field(default_factory=dict)


app = FastAPI(title="Turnstile backend example")


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
