#!/usr/bin/env bash
# 가입 → 로그인 → 근거 분석 → 저장 → 목록 → 남의 경로 거부까지 한 번에 확인 (RGN6-127 보조자료, 정강태 시험용).
# 사용: scripts/api_smoke.sh [서버주소]   예) scripts/api_smoke.sh http://localhost:8081
# 비밀번호는 실행할 때마다 새로 만든다(저장소에 계정 정보를 두지 않음).
# TMAP 키가 없으면 근거 분석·저장은 503이 정상이고, 그때는 저장 이후 단계를 건너뛴다.
set -euo pipefail
B=${1:-http://localhost:8081}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
PW=$(python3 -c 'import secrets;print(secrets.token_urlsafe(16))')
SUF=$(date +%s)
fail() { echo "실패: $*" >&2; exit 1; }

token() { curl -s -c "$1" -b "$1" "$B/api/auth/csrf" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'; }
code() { local jar=$1; shift; curl -s -o "$W/body" -w '%{http_code}' -c "$jar" -b "$jar" -H "X-XSRF-TOKEN: $(token "$jar")" "$@"; }
login() { # $1 쿠키 파일, $2 아이디
  local c
  c=$(code "$1" -H 'Content-Type: application/json' -d "{\"loginId\":\"$2\",\"password\":\"$PW\",\"nickname\":\"시험 보호자\",\"age14OrOlder\":true}" "$B/api/auth/signup")
  [ "$c" = 201 ] || fail "가입 $2: $c $(cat "$W/body")"
  c=$(code "$1" --data-urlencode "username=$2" --data-urlencode "password=$PW" "$B/api/auth/login")
  [ "$c" = 200 ] || fail "로그인 $2: $c"
  echo "가입·로그인 $2: 성공"
}

OWNER=qa_owner_$SUF; OTHER=qa_other_$SUF
login "$W/owner" "$OWNER"
login "$W/other" "$OTHER"

c=$(code "$W/anon" -H 'Content-Type: application/json' -d '{"start":{"lat":37.59867,"lng":127.1},"end":{"lat":37.5998,"lng":127.1036},"candidateId":"candidate-0","name":"익명"}' "$B/api/routes")
[ "$c" = 401 ] || fail "로그인 없이 저장이 $c"; echo "로그인 없이 저장: 401 거부"

c=$(code "$W/owner" -H 'Content-Type: application/json' -d '{"start":{"lat":37.59867,"lng":127.1},"end":{"lat":37.5998,"lng":127.1036},"candidateId":"candidate-99","name":"꾸민 후보"}' "$B/api/routes")
[ "$c" = 400 ] || fail "꾸민 후보가 $c"; echo "꾸민 후보: 400 거부"

c=$(code "$W/owner" -H 'Content-Type: application/json' -d '{"start":{"lat":37.59867,"lng":127.1},"end":{"lat":37.5998,"lng":127.1036}}' "$B/api/routes/evidence")
if [ "$c" = 503 ]; then echo "근거 분석: 503 (TMAP 키 없음·한도 초과). 저장 이후 단계는 건너뜀"; exit 0; fi
[ "$c" = 200 ] || fail "근거 분석: $c"; echo "근거 분석: 200"

c=$(code "$W/owner" -H 'Content-Type: application/json' -d '{"start":{"lat":37.59867,"lng":127.1},"end":{"lat":37.5998,"lng":127.1036},"candidateId":"candidate-0","name":"망우초 가는 길"}' "$B/api/routes")
[ "$c" = 201 ] || fail "저장: $c $(cat "$W/body")"
ID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$W/body"); echo "저장: 201 (id $ID)"

[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$W/owner" "$B/api/routes/$ID")" = 200 ] || fail "본인 상세"
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$W/other" "$B/api/routes/$ID")" = 404 ] || fail "남의 경로가 보임"
echo "본인 상세 200, 남의 경로 404: 성공"
