# 통학 안전·안내 서비스 — Should·Could

Should·Could 검증용 앱입니다. 아래 명령은 이 폴더에서 실행합니다. 팀 Jira RGN6-169~177(Should)과 RGN6-178~196(Could)를 대상으로 검증한다. 이 브랜치는 Java 21, Spring Boot, Thymeleaf와 별도 MySQL을 사용한다.

```sh
cp .env.example .env
# .env에 앱 DB 비밀번호, MySQL 관리자 비밀번호와 필요한 API 설정을 입력한다.
docker compose up -d --wait
./gradlew bootRun
```

브라우저에서 `http://127.0.0.1:8080`을 연다. 개발 DB는 Compose의 독립 볼륨과 127.0.0.1:3308을 사용한다. 실제 경로와 검색에는 제공자 키가 필요하다. 실제 위치·푸시·설치 확인에는 HTTPS와 휴대폰이 필요하다.

```sh
./gradlew test bootJar
node --test src/test/js/*.test.cjs
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Jira의 실기기 시험은 자동 시험과 별도로 판정한다. 시험 DB, 인증 키, 기기 데이터는 환경 파일이나 저장소 밖에 둔다.
