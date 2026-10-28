# 통학 안전·안내 서비스 팀 프로젝트

지역문제해결 미니캡스톤 공용 저장소의 초기 프로젝트입니다.

## 개발 환경

Java 21, Spring Boot 3.5.6, Gradle 8.14.3 Wrapper를 사용합니다. MySQL 8 개발용 compose 파일을 포함합니다. 초기 서버는 DB 없이 실행할 수 있습니다. 회원·공공데이터·경로·지도 모듈은 후속 개발에서 반영합니다.

## 실행

```sh
git clone https://github.com/minch05/safe-school-road-team.git
cd safe-school-road-team
./gradlew bootRun
```

http://localhost:8081 에서 프로젝트 응답, `/health/setup`에서 초기 실행 상태를 확인합니다. 종료는 Ctrl+C입니다.

```sh
./gradlew test bootJar
```

Windows에서는 `gradlew.bat`를 사용합니다. 처음 실행에는 의존성 다운로드가 필요합니다.

## DB 준비

개발 중에는 팀원 각자가 자기 컴퓨터의 DB를 사용합니다. `.env.example`을 `.env`로 복사하고 개발 DB 비밀번호를 설정한 뒤 `docker compose up -d`를 실행합니다. 기존 MySQL이 3306에서 실행 중이면 Docker DB를 중복 실행하지 않습니다. 초기 웹 서버는 아직 DB에 연결되지 않습니다. 데이터 모듈 추가 때 DB 연결·스키마를 함께 검증합니다.

## 서비스 범위

개발 목표는 강서·광진·동대문·중랑·관악·강북·구로·성북·종로·영등포·양천·중구의 12개 구 정식 지원입니다. 그 외 구는 이용을 막지 않고 자료 부족 안내를 표시합니다. 현재 초기 서버에는 이 기능이 아직 구현되지 않았습니다.

## 공동 작업

- 팀원이 clone한 뒤 빌드·실행을 확인합니다. 저장소 쓰기 권한 초대는 GitHub 아이디 확인 후 진행합니다.
- 이슈 단위 브랜치를 만들고 Pull Request로 변경을 검토합니다.
- GitHub Actions에서 테스트와 JAR 빌드를 수행합니다.
- `.env`, API 키, 사용자 좌표, 기기 구독 파일, 공공데이터 원본은 커밋하지 않습니다.

## 사전 시험

`experiments/https-location`은 회원 DB·API 키와 분리된 HTTPS 위치 읽기 시험 페이지입니다. 실행과 실제 휴대폰 시험 방법은 해당 폴더의 README를 참고합니다.
