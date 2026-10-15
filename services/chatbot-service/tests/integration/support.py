import time

import jwt


def bearer(user, secret="test-only-secret-0123456789-0123456789-0123456789", roles=("BUYER",)):
    claims = {"iss": "auth-service", "sub": user, "roles": list(roles), "exp": int(time.time()) + 600}
    return {"Authorization": "Bearer " + jwt.encode(claims, secret, algorithm="HS256")}
