"""ladder.tsv(journeyId, userId[, 그 밖 칸은 무시]) 에 access JWT 를 붙여 k6 가 읽을 JSON 으로 낸다.
usage: python3 -I tokens.py ladder.tsv out.json   (env AUTH_JWT_SECRET, AUTH_JWT_ISSUER=nextroute)"""
import base64, hashlib, hmac, json, os, sys, time

secret = os.environ["AUTH_JWT_SECRET"].encode()
issuer = os.environ.get("AUTH_JWT_ISSUER", "nextroute")


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def token(user_id):
    now = int(time.time())
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64(json.dumps({"iss": issuer, "sub": str(user_id), "type": "access", "gen": 0,
                           "iat": now, "exp": now + 6 * 3600}).encode())
    sig = b64(hmac.new(secret, f"{header}.{body}".encode(), hashlib.sha256).digest())
    return f"{header}.{body}.{sig}"


rows = []
for line in open(sys.argv[1]):
    journey, user = line.split()[:2]
    rows.append({"journeyId": int(journey), "token": token(int(user))})
json.dump(rows, open(sys.argv[2], "w"))
print(len(rows), "tokens")
