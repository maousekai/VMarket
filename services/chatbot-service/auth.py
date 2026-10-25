"""Identity comes only from a verified access token, never from the request body or X-User-* headers.

The chat endpoint is public at the gateway (guests may ask general questions), so the gateway does
not validate its token. This service verifies it with the same rules as the gateway and
order-service: HS256 with the shared AUTH_JWT_SECRET, issuer auth-service, 30 seconds clock skew.
"""
from dataclasses import dataclass

import jwt

from schemas import ChatError

ISSUER = "auth-service"
BEARER = "Bearer "


@dataclass(frozen=True)
class Identity:
    user_id: str
    roles: frozenset
    authorization: str  # Forwarded unchanged so order-service scopes the lookup to this user itself.

    @property
    def admin(self):
        return "ADMIN" in self.roles


def identify(authorization, secret):
    """No header means a guest; a header that does not verify is rejected so the client can refresh."""
    if authorization is None:
        return None
    token = authorization[len(BEARER):].strip() if authorization.startswith(BEARER) else ""
    try:
        claims = jwt.decode(token, secret, algorithms=["HS256"], issuer=ISSUER, leeway=30,
                            options={"require": ["exp", "iss", "sub"]})
    except jwt.PyJWTError:
        raise ChatError(401, "UNAUTHORIZED", "Token không hợp lệ hoặc đã hết hạn") from None
    user_id = claims["sub"]
    if not isinstance(user_id, str) or not user_id.strip():
        raise ChatError(401, "UNAUTHORIZED", "Token không hợp lệ hoặc đã hết hạn")
    roles = claims.get("roles")
    return Identity(user_id, frozenset(r for r in roles if isinstance(r, str)) if isinstance(roles, list) else frozenset(),
                    BEARER + token)


def require(identity):
    if identity is None:
        raise ChatError(401, "UNAUTHORIZED", "Vui lòng đăng nhập để dùng chức năng này")
    return identity
