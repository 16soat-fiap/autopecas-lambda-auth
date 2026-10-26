#!/usr/bin/env python3
"""Aguarda a publicacao de discovery/JWKS antes de criar o authorizer JWT."""
import json
import os
import time
import urllib.error
import urllib.request

issuer = os.environ["JWT_ISSUER_URL"]
discovery_url = issuer + "/.well-known/openid-configuration"
jwks_url = issuer + "/.well-known/jwks.json"

for attempt in range(1, 16):
    try:
        with urllib.request.urlopen(discovery_url, timeout=15) as response:
            discovery = json.load(response)
        if discovery.get("issuer") != issuer or discovery.get("jwks_uri") != jwks_url:
            raise ValueError("Discovery nao corresponde ao issuer configurado")
        with urllib.request.urlopen(jwks_url, timeout=15) as response:
            jwks = json.load(response)
        if not any(key.get("kty") == "RSA" and key.get("kid") and key.get("n") and key.get("e")
                   for key in jwks.get("keys", [])):
            raise ValueError("JWKS nao contem chave publica RSA")
        print("Discovery e JWKS disponiveis.")
        break
    except (urllib.error.URLError, TimeoutError, ValueError):
        if attempt == 15:
            raise SystemExit("Discovery/JWKS ainda indisponiveis; authorizer nao criado.")
        print(f"Aguardando publicacao de discovery/JWKS: tentativa {attempt}/15", flush=True)
        time.sleep(2)
