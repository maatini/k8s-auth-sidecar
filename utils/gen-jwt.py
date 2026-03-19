#!/usr/bin/env python3
"""
Erzeugt RS256 JWT + JWKS für Quarkus OIDC + WireMock Tests
→ User-Claims komplett aus YAML
"""

import json
import argparse
from pathlib import Path
import yaml
import base64
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.backends import default_backend


def generate_key_pair():
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
        backend=default_backend()
    )
    public_key = private_key.public_key()

    pem_private = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    ).decode("utf-8")

    pem_public = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode("utf-8")

    return pem_private, pem_public, private_key


def jwk_from_public_key(public_key_pem: str, kid: str = "test-key-2026") -> dict:
    pub_key = serialization.load_pem_public_key(public_key_pem.encode(), default_backend())
    numbers = pub_key.public_numbers()

    def int_to_b64url(val):
        return base64.urlsafe_b64encode(
            val.to_bytes((val.bit_length() + 7) // 8, "big")
        ).rstrip(b"=").decode()

    return {
        "kty": "RSA",
        "kid": kid,
        "use": "sig",
        "alg": "RS256",
        "n": int_to_b64url(numbers.n),
        "e": int_to_b64url(numbers.e)
    }


def create_test_jwt(private_key, claims: dict, kid: str = "test-key-2026") -> str:
    headers = {"alg": "RS256", "typ": "JWT", "kid": kid}
    return jwt.encode(payload=claims, key=private_key, algorithm="RS256", headers=headers)


def main():
    parser = argparse.ArgumentParser(description="JWT-Generator mit YAML-User-Config")
    parser.add_argument("user_yaml", type=Path, help="YAML-Datei mit User-Claims")
    parser.add_argument("--kid", default="test-key-2026")
    parser.add_argument("--iss", default="http://localhost:8090/realms/master")
    parser.add_argument("--aud", default="k8s-auth-sidecar")
    parser.add_argument("--iat", type=int, default=1771572840)
    parser.add_argument("--exp", type=int, default=2086932840)
    args = parser.parse_args()

    # YAML laden
    with open(args.user_yaml, encoding="utf-8") as f:
        user = yaml.safe_load(f)

    # Pflichtfelder prüfen
    for field in ["sub", "email", "name", "preferred_username", "groups"]:
        if field not in user:
            raise ValueError(f"Fehlender Pflicht-Claim in YAML: {field}")

    claims = {
        "sub": user["sub"],
        "email": user["email"],
        "name": user["name"],
        "preferred_username": user["preferred_username"],
        "groups": user["groups"],
        "tenant": user.get("tenant", "tenant-A"),
        "iss": args.iss,
        "aud": args.aud,
        "iat": args.iat,
        "exp": args.exp,
    }

    private_pem, public_pem, private_key_obj = generate_key_pair()
    jwk = jwk_from_public_key(public_pem, kid=args.kid)
    jwks = {"keys": [jwk]}

    token = create_test_jwt(private_key_obj, claims, kid=args.kid)

    print("Private Key (PEM) – speichere sicher!\n")
    print(private_pem)
    print("\nPublic Key (PEM):\n")
    print(public_pem)
    print("\nJWKS JSON (für WireMock /.well-known/jwks.json):\n")
    print(json.dumps(jwks, indent=2))
    print("\nBeispiel-JWT (kopiere direkt in Authorization: Bearer ...):\n")
    print(token)
    print("\nFertig! Issuer:", args.iss)


if __name__ == "__main__":
    main()

    