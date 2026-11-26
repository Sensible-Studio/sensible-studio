#!/usr/bin/env bash
# OAuth on an instance (A, default the author on 4502), end to end, as an application would: it registers
# two clients, allows one as admin, uses and refreshes the tokens, and removes both clients again. Secrets
# and tokens are never printed. slingshot1 (a WKND sample user) is the user the second client acts as.
set -uo pipefail
A=${A:-http://127.0.0.1:4502}
AU="-u admin:admin"
j() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1 ($2)"; else echo "FAIL  $1: got $2, want $3"; fails=$((fails+1)); fi; }

echo "== a client registered by an administrator"
R=$(curl -s $AU --data-urlencode op=createClient --data-urlencode "name=CLI rehearsal" --data-urlencode redirectUris=http://localhost:8765/cb \
  --data-urlencode scope=read --data-urlencode scope=write --data-urlencode scope=offline_access $A/bin/studio/oauth.json)
ID=$(echo "$R" | j 'd["client"]["id"]'); SECRET=$(echo "$R" | j 'd["client"]["secret"]')
check "client made" "$(echo "$R" | j 'd["client"]["name"]')" "CLI rehearsal"
check "the secret is not listed afterwards" "$(curl -s $AU "$A/bin/studio/oauth.json?op=clients" | grep -c "$SECRET")" 0

echo "== a user allows it (authorization code with PKCE)"
VERIFIER=$(python3 -c 'import secrets;print(secrets.token_urlsafe(48))')
CHALLENGE=$(python3 -c 'import sys,hashlib,base64;print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).rstrip(b"=").decode())' "$VERIFIER")
Q="response_type=code&client_id=$ID&redirect_uri=http%3A%2F%2Flocalhost%3A8765%2Fcb&scope=read%20write%20offline_access&state=xyz&code_challenge=$CHALLENGE&code_challenge_method=S256"
check "not signed in: to the login page" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$A/oauth/authorize?$Q" | cut -c1-60)" "302 http://127.0.0.1:4502/libs/granite/core/content/login.html"
check "signed in: what it asks for" "$(curl -s $AU "$A/oauth/authorize?$Q" | grep -c 'asks to act as you')" 1
check "a wrong redirect address is refused here" "$(curl -s -o /dev/null -w '%{http_code}' $AU "$A/oauth/authorize?${Q/localhost%3A8765/evil.example}")" 400
check "without PKCE refused" "$(curl -s -o /dev/null -w '%{http_code}' $AU "$A/oauth/authorize?response_type=code&client_id=$ID&redirect_uri=http%3A%2F%2Flocalhost%3A8765%2Fcb")" 400
check "the answer from another site refused" "$(curl -s -o /dev/null -w '%{http_code}' $AU -H 'Origin: https://evil.example' --data "$Q&decision=allow" $A/oauth/authorize)" 403
LOC=$(curl -s -o /dev/null -w '%{redirect_url}' $AU -H "Origin: $A" --data "$Q&decision=allow" $A/oauth/authorize)
CODE=$(python3 -c 'import sys,urllib.parse as u; print(u.parse_qs(u.urlparse(sys.argv[1]).query)["code"][0])' "$LOC")
check "back to the application with the state" "$(python3 -c 'import sys,urllib.parse as u; q=u.parse_qs(u.urlparse(sys.argv[1]).query); print(u.urlparse(sys.argv[1]).netloc, q["state"][0])' "$LOC")" "localhost:8765 xyz"
DENY=$(curl -s -o /dev/null -w '%{redirect_url}' $AU -H "Origin: $A" --data "$Q&decision=deny" $A/oauth/authorize)
check "denied: access_denied" "$(echo "$DENY" | grep -o 'error=access_denied')" "error=access_denied"

echo "== the application exchanges the code"
T=$(curl -s --data-urlencode grant_type=authorization_code --data-urlencode "code=$CODE" --data-urlencode redirect_uri=http://localhost:8765/cb \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=$ID" --data-urlencode "client_secret=$SECRET" $A/oauth/token)
ACCESS=$(echo "$T" | j 'd["access_token"]'); REFRESH=$(echo "$T" | j 'd["refresh_token"]')
check "tokens" "$(echo "$T" | j 'd["token_type"], d["expires_in"], d["scope"]')" "Bearer 3600 read write offline_access"
check "the code a second time" "$(curl -s --data-urlencode grant_type=authorization_code --data-urlencode "code=$CODE" --data-urlencode redirect_uri=http://localhost:8765/cb --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=$ID" --data-urlencode "client_secret=$SECRET" $A/oauth/token | j 'd["error"]')" invalid_grant
check "a wrong secret" "$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode grant_type=client_credentials --data-urlencode "client_id=$ID" --data-urlencode client_secret=wrong $A/oauth/token)" 401

echo "== the token at work"
check "anonymous: a page on the author" "$(curl -s -o /dev/null -w '%{http_code}' $A/content/wknd/us/en.html)" 401
check "with the token" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" $A/content/wknd/us/en.html)" 200
check "as whom" "$(curl -s -H "Authorization: Bearer $ACCESS" $A/libs/granite/security/currentuser.json | j 'd["authorizableId"]')" admin
check "an API" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" "$A/bin/studio/replication.json?op=agents")" 200
check "the OAuth console not with a token" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" "$A/bin/studio/oauth.json?op=clients")" 403
check "a forged token" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer sst_forged' $A/content/wknd/us/en.html)" 401

echo "== refresh: narrower, once"
T2=$(curl -s --data-urlencode grant_type=refresh_token --data-urlencode "refresh_token=$REFRESH" --data-urlencode scope=read --data-urlencode "client_id=$ID" --data-urlencode "client_secret=$SECRET" $A/oauth/token)
READONLY=$(echo "$T2" | j 'd["access_token"]')
check "a read-only token" "$(echo "$T2" | j 'd["scope"]')" read
check "read-only: GET" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $READONLY" $A/content/wknd/us/en.html)" 200
check "read-only: POST refused" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $READONLY" -X POST $A/libs/granite/security/currentuser.json)" 403
check "write: POST goes on to the servlet" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" -X POST $A/libs/granite/security/currentuser.json | sed 's/403/refused/')" "$(curl -s -o /dev/null -w '%{http_code}' $AU -X POST $A/libs/granite/security/currentuser.json)"
check "the old refresh token again" "$(curl -s --data-urlencode grant_type=refresh_token --data-urlencode "refresh_token=$REFRESH" --data-urlencode "client_id=$ID" --data-urlencode "client_secret=$SECRET" $A/oauth/token | j 'd["error"]')" invalid_grant
check "and the whole grant ended" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $READONLY" $A/content/wknd/us/en.html)" 401

echo "== a client acting as a user of its own"
R2=$(curl -s $AU --data-urlencode op=createClient --data-urlencode "name=Indexer rehearsal" --data-urlencode scope=read --data-urlencode runAs=slingshot1 $A/bin/studio/oauth.json)
ID2=$(echo "$R2" | j 'd["client"]["id"]'); SECRET2=$(echo "$R2" | j 'd["client"]["secret"]')
check "never as admin" "$(curl -s -o /dev/null -w '%{http_code}' $AU --data-urlencode op=createClient --data-urlencode name=x --data-urlencode runAs=admin $A/bin/studio/oauth.json)" 400
T3=$(curl -s --data-urlencode grant_type=client_credentials --data-urlencode "client_id=$ID2" --data-urlencode "client_secret=$SECRET2" $A/oauth/token)
SVC=$(echo "$T3" | j 'd["access_token"]')
check "as slingshot1" "$(curl -s -H "Authorization: Bearer $SVC" $A/libs/granite/security/currentuser.json | j 'd["authorizableId"]')" slingshot1
check "slingshot1 cannot read the OAuth records" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $SVC" $A/var/studio/oauth/clients.1.json)" 404
check "nor the console" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $SVC" "$A/bin/studio/oauth.json?op=tokens")" 403

echo "== the console"
check "tokens listed" "$(curl -s $AU "$A/bin/studio/oauth.json?op=tokens" | j 'len([t for t in d["tokens"] if t["client"] in ("'$ID'","'$ID2'")])')" 1
TID=$(curl -s $AU "$A/bin/studio/oauth.json?op=tokens" | j '[t["id"] for t in d["tokens"] if t["client"]=="'$ID2'"][0]')
curl -s -o /dev/null $AU --data-urlencode op=revoke --data-urlencode "id=$TID" $A/bin/studio/oauth.json
check "revoked in the console" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $SVC" $A/libs/granite/security/currentuser.json)" 401
for c in "$ID" "$ID2"; do curl -s -o /dev/null $AU --data-urlencode op=deleteClient --data-urlencode "id=$c" $A/bin/studio/oauth.json; done
check "clients removed" "$(curl -s $AU "$A/bin/studio/oauth.json?op=clients" | j 'len(d["clients"])')" 0
check "the token with them" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" $A/content/wknd/us/en.html)" 401
echo "== $fails failed"
