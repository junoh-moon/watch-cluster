# Watch-Cluster

Kubernetes용 자동 컨테이너 이미지 업데이트 도구입니다. Docker의 Watchtower와 유사한 기능을 Kubernetes 환경에서 제공합니다.

## 주요 기능

- Annotation 기반 선택적 모니터링
- 시맨틱 버저닝 기반 자동 업데이트
- Latest 태그 이미지의 변경 감지 및 업데이트
- 임의 태그(stable, release-candidate 등)의 다이제스트 기반 업데이트 감지
- Cron 표현식을 사용한 유연한 스케줄링
- Deployment로 배포되어 클러스터에서 실행
- 롤링 업데이트 완료까지 대기
- 웹훅을 통한 이벤트 알림 (deployment 감지, 이미지 롤아웃 상태)
- 감시 현황 조회와 수동 업데이트를 위한 내장 웹 UI

## 시작하기

### 사전 요구사항

- Kubernetes 클러스터 (v1.20+)
- kubectl 설치 및 클러스터 접근 권한
- Docker (이미지 빌드용)
- 프라이빗 레지스트리 사용 시 imagePullSecret 설정 필요

### Kubernetes 매니페스트 파일

`k8s/` 디렉토리에는 다음 파일들이 포함되어 있습니다:

- `namespace.yaml`: watch-cluster 네임스페이스 생성
- `rbac.yaml`: ServiceAccount, ClusterRole, ClusterRoleBinding 설정
- `configmap.yaml`: 웹훅 설정을 위한 ConfigMap
- `deployment.yaml`: watch-cluster 애플리케이션 배포 설정
- `service.yaml`: 웹 UI를 노출하는 ClusterIP Service
- `ingress.yaml`: Basic Auth, TLS, Flame 연동이 적용된 HAProxy Ingress
- `create_account.example.sh`: 관리자 계정 생성·수정용 스크립트 예시
- `example-deployment.yaml`: 테스트용 예시 애플리케이션 (버전 태그)
- `example-deployment-stable.yaml`: 임의 태그(stable, custom tag) 사용 예시

### 설치

```bash
# 1. 소스 클론
git clone https://github.com/your-org/watch-cluster.git
cd watch-cluster

# 2. Docker 이미지 빌드
docker build -t watch-cluster:latest .

# 3. 이미지를 레지스트리에 푸시 (선택사항)
docker tag watch-cluster:latest your-registry/watch-cluster:latest
docker push your-registry/watch-cluster:latest

# 4. Kubernetes에 배포
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml

# 5. 웹 UI 노출 (선택사항)
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml   # host를 먼저 수정하십시오
```

### 기존 설치 업그레이드

이미 설치된 watch-cluster를 업그레이드하는 경우, 업데이트 확인을 기대하기 전에 RBAC 매니페스트를 다시 적용하십시오:

```bash
kubectl apply -f k8s/rbac.yaml
```

`latest` 전략은 이미지 다이제스트를 비교하기 전에 노드 플랫폼을 확인하므로 watch-cluster ServiceAccount에 core `nodes` 리소스 `get` 권한이 필요합니다. 이 권한이 없으면 platform digest 조회가 실패하여 임의 태그 업데이트가 건너뛰어질 수 있습니다.

### 설치 확인

```bash
# Deployment 상태 확인
kubectl get deployment -n watch-cluster watch-cluster

# 로그 확인
kubectl logs -n watch-cluster -l app=watch-cluster
```

### 예시 애플리케이션 배포

watch-cluster의 동작을 테스트하려면 예시 애플리케이션을 배포할 수 있습니다:

```bash
# 예시 애플리케이션 배포
kubectl apply -f k8s/example-deployment.yaml

# 예시 애플리케이션 상태 확인
kubectl get deployment example-app
kubectl logs -n watch-cluster -l app=watch-cluster
```

## 사용 방법

### 기본 사용 예시

Deployment에 annotation을 추가하여 자동 업데이트를 활성화합니다:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  annotations:
    watch-cluster.io/enabled: "true"
    watch-cluster.io/cron: "*/30 * * * *"    # 30분마다 확인
    watch-cluster.io/strategy: "version"      # 버전 기반 업데이트
spec:
  replicas: 3
  selector:
    matchLabels:
      app: my-app
  template:
    metadata:
      labels:
        app: my-app
    spec:
      containers:
      - name: app
        image: myregistry/myapp:1.0.0
        ports:
        - containerPort: 8080
```

### Annotation 설명

| Annotation | 설명 | 필수 | 기본값 |
|------------|------|------|--------|
| `watch-cluster.io/enabled` | 모니터링 활성화 여부 | 예 | - |
| `watch-cluster.io/cron` | 업데이트 확인 주기 (Unix cron) | 아니오 | `*/5 * * * *` |
| `watch-cluster.io/strategy` | 업데이트 전략 (`version`, `version-lock-major`, `latest`) | 아니오 | `version` |
| `watch-cluster.io/check-now` | 즉시 업데이트 확인을 한 번 실행한 뒤 이 annotation 제거 | 아니오 | - |

### 수동 확인 트리거

다음 cron 실행을 기다리지 않고 동일한 확인을 한 번 실행하려면 모니터링 중인 Deployment에 `watch-cluster.io/check-now` annotation을 추가합니다:

```bash
kubectl annotate deployment -n my-namespace my-app watch-cluster.io/check-now=true --overwrite
```

watch-cluster는 요청을 감지하면 annotation을 제거하고, Deployment의 기존 전략으로 한 번 확인한 뒤 요청과 결과를 Kubernetes Event로 기록합니다.

감사 내역은 다음 명령으로 확인할 수 있습니다:

```bash
kubectl get events -n my-namespace --sort-by=.metadata.creationTimestamp
```

### 최소 릴리스 경과 시간

선택한 Docker Hub 이미지의 마지막 push 이후 일정 시간이 지날 때까지 업데이트를 유예할 수 있습니다.

```yaml
annotations:
  watch-cluster.io/minimum-release-age: "3d"
```

양의 정수와 `h`, `d`, `w`를 조합하십시오. 예시는 `12h`, `3d`, `3w`이며, 1일은 24시간, 1주는 7일입니다.
미설정 또는 `0`이면 유예하지 않습니다. 소수·복합 단위·공백·오버플로를 포함한 잘못된 값은 경고를 기록하고 무시합니다.
Deployment annotation으로 설정하십시오. 웹 UI에서 설정값과 확인 결과를 볼 수 있습니다.

기존 업데이트 전략에서 허용하는 최신 후보만 판단하며, 대기 중 새 후보가 나오면 이전 후보를 건너뜁니다.
최초 발견 시각이나 빌드 시각 대신 레지스트리의 마지막 push 시각을 사용합니다. 태그/인덱스 digest는 `tag_last_pushed`,
플랫폼 이미지 digest는 일치하는 `images[].last_pushed`를 사용합니다. 이 값은 최초 공개 시각을 보장하지 않습니다.

시각 누락·digest 불일치·미지원 레지스트리·조회 오류는 적용을 보류하고 다음 확인에서 재시도합니다.
UI에 `유예 대기` 또는 `공개 시각 확인 불가`를 표시하며, 확인 이력에 후보와 확인 가능한 적용 가능 시각을 기록합니다.
자동 적용은 유예 종료 후 다음 cron 실행에서 이루어지며, `check-now`도 같은 조건을 따릅니다.
대기 타이머를 저장하지 않으므로 재시작 후에도 레지스트리 시각을 다시 조회하여 판단합니다.

유예를 통과한 이미지는 태그와 검증한 digest를 함께 지정하여 적용합니다. `latest` 전략도 동일합니다.
확인 후 태그가 바뀌어 유예를 검증하지 않은 이미지가 pull되는 것을 방지하기 위한 동작입니다.
미설정 또는 `0`이면 기존 업데이트 동작을 유지합니다.
현재 `latest` 전략은 관측한 플랫폼 이미지 digest를 고정하므로, 해당 Deployment가 실행될 노드의 아키텍처가 같을 때만 유예를 사용하십시오.
멀티 아키텍처 index 선택을 유지하는 개선은 후속 작업으로 남겨 두었습니다.

`MINIMUM_RELEASE_AGE_EXCLUDED_REGISTRIES` 환경 변수에 쉼표로 구분한 정확한 레지스트리 호스트를 지정하면 항상 유예를 건너뜁니다.
제공된 ConfigMap은 `hub.sixtyfive.me`를 제외하며, 애플리케이션 코드의 기본 제외 목록은 비어 있습니다.
업그레이드 시 이 환경 설정을 사용하려면 ConfigMap과 Deployment 설정을 모두 반영하십시오.
그 밖의 Docker Hub가 아닌 레지스트리는 양의 유예 기간이 설정되어 있으면 보류되므로, 필요에 따라 `0` 또는 명시적 제외를 사용하십시오.

### 업데이트 전략

#### 1. Version 전략
시맨틱 버저닝을 사용하는 이미지에 적합합니다:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "version"
```

지원되는 버전 형식:
- `1.0.0`, `1.0.1`, `1.1.0`
- `v1.0.0`, `v1.0.1`, `v1.1.0`
- `1.0.0-beta`, `1.0.0-rc1`

`12.0ubu2604-ls48` 같은 태그는 앞의 숫자 버전(`12.0`)만 비교하며, 이미지 업데이트 시 패키징 접미사를 유지합니다. 접미사만 바뀌면 업데이트하지 않습니다. `12.0rc1`처럼 바로 붙은 형태를 포함하여 `rc`, `alpha`, `beta` 사전 릴리스는 업데이트 후보에서 제외합니다.

#### 2. Version Lock Major 전략
Major 버전을 고정하고 Minor/Patch 버전만 업데이트합니다:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "version-lock-major"
```

예시:
- 현재 버전이 `v1.0.0`인 경우, `v1.1.0`이나 `v1.0.1`로는 업데이트되지만 `v2.0.0`으로는 업데이트되지 않습니다.
- 현재 버전이 `v0.5.0`인 경우, `v0.6.0`이나 `v0.5.1`로는 업데이트되지만 `v1.0.0`으로는 업데이트되지 않습니다.

#### 3. Latest 전략
`latest` 태그 또는 버전이 아닌 임의의 태그를 사용하는 이미지에 적합합니다:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "latest"
```

이미지 다이제스트를 비교하여 실제 변경 사항을 감지합니다. 다음과 같은 태그들을 지원합니다:
- `latest` - 가장 최신 빌드
- `stable` - 안정 버전
- `release-candidate` - 릴리스 후보
- `release-openvino` - 특정 프레임워크용 릴리스
- `dev`, `nightly`, `edge` - 개발/실험 버전
- 기타 버전 형식이 아닌 모든 태그

**참고**: 버전 형식의 태그(예: `v1.0.0`, `1.2.3`)는 Version 전략을 사용해야 합니다.

### Cron 표현식 예시

5필드 Unix cron 형식을 사용합니다: `분 시 일 월 요일`.

| 표현식 | 설명 |
|--------|------|
| `*/5 * * * *` | 5분마다 |
| `0 * * * *` | 매시간 정각 |
| `0 2 * * *` | 매일 오전 2시 |
| `0 9-17 * * MON-FRI` | 평일 9시-17시 매시간 |
| `0 0 * * MON` | 매주 월요일 자정 |
| `0 0 1 * *` | 매월 1일 자정 |

`*/N`은 고정 간격 타이머가 아니라 해당 필드에서 `N` 간격으로 매칭되는 값입니다. 예를 들어 `*/7 * * * *`는 매시간 `0, 7, 14, ..., 56`분에 실행됩니다.

## 웹 UI

watch-cluster는 컨트롤러와 동일한 프로세스에서 8080 포트로 관리 UI와 JSON API를
제공합니다. 스케줄 상태와 체크 이력은 컨트롤러 메모리에만 존재하므로, 같은
프로세스에 두어야 annotation만으로는 알 수 없는 정보까지 보여줄 수 있습니다.

```bash
kubectl apply -f k8s/service.yaml

# Ingress 없이 확인
kubectl port-forward -n watch-cluster svc/watch-cluster 8080:80
open http://localhost:8080
```

UI에서 할 수 있는 일:

- 감시 중인 Deployment 목록을 전략·스케줄·다음 체크 시각·마지막 체크 결과와 함께 조회
- 행을 펼쳐 체크 이력, Kubernetes 이벤트 확인 및 cron/전략 즉시 수정
- 수동 체크 트리거
- 클러스터의 미적용 Deployment 중에서 골라 새 앱 감시 시작

### API 레퍼런스

| Method | Path | 설명 |
|--------|------|------|
| `GET` | `/api/apps` | 감시 중인 deployment의 스케줄·체크 상태 |
| `GET` | `/api/apps/{ns}/{name}` | 상세 정보 (메모리 체크 이력 + Kubernetes 이벤트) |
| `POST` | `/api/apps/{ns}/{name}/check` | 체크 1회 트리거 (202, 비동기 실행) |
| `PUT` | `/api/apps/{ns}/{name}/watch` | 감시 시작 또는 `{"cron": "...", "strategy": "..."}` 수정. 생략한 필드는 기존 값 유지 |
| `DELETE` | `/api/apps/{ns}/{name}/watch` | 감시 해제 (`enabled: "false"`, cron/전략은 보존) |
| `GET` | `/api/deployments` | 아직 감시하지 않는 deployment 후보 목록 |
| `GET` | `/healthz`, `/readyz` | 헬스 프로브 (인증 대상 아님) |

모든 쓰기는 `kubectl annotate`가 설정하는 것과 동일한 `watch-cluster.io/*`
annotation을 거치므로 UI·API·CLI가 하나의 경로를 공유합니다. 잘못된 cron
표현식이나 알 수 없는 전략은 기본값으로 대체하지 않고 400으로 거부합니다.

### 인증

`k8s/ingress.yaml`은 `https://watch.sixtyfive.me`의 UI와 `/api/*`를
HAProxy Basic Auth로 보호합니다. TLS는 cert-manager의 `letsencrypt`
ClusterIssuer를 사용합니다. HTTP에서 인증을 요구하기 전에 HTTPS로 전환하도록
해당 문제가 수정된 HAProxy Kubernetes Ingress Controller 3.2.13 이상의
3.2 버전을 사용하십시오.

Ingress 적용 전에 인증 Secret을 생성하십시오. 스크립트에는 `kubectl`,
`openssl`, `jq`가 필요하며, `watch-cluster` 네임스페이스가 존재해야 합니다:

```bash
cp -n k8s/create_account.example.sh k8s/create_account.sh
# k8s/create_account.sh의 ACCOUNT_ID와 ACCOUNT_PASSWORD를 입력한 후 실행하십시오.
sh k8s/create_account.sh
```

로컬 실행 파일은 Git에서 제외됩니다. 추적되는 예시 파일에는 계정 정보를 입력하지
마십시오. 스크립트는 `watch-cluster-basic-auth`에 비밀번호 해시를 저장하고 다른
계정을 유지합니다. 같은 ID/PW로 재실행하면 변경하지 않습니다. 비밀번호를 변경하려면
로컬 파일을 수정해 다시 실행하십시오. ID를 바꾸면 별도 계정이 추가됩니다.

Basic Auth는 Ingress를 경유하는 요청을 보호합니다. 클러스터 내부에서 Service로
직접 접근하는 경로에는 적용되지 않습니다. 이 매니페스트에는 NetworkPolicy나
인증 실패 횟수에 따른 차단 설정이 포함되어 있지 않습니다.

애플리케이션은 `/api/*`에 선택적으로 `ADMIN_TOKEN` 인증을 적용할 수 있습니다.
설정하면 UI가 Bearer 토큰을 입력받아 `localStorage`에 보관합니다. 위의 Ingress
Basic Auth 구성에서는 이 값을 설정하지 마십시오. 두 인증 방식 모두
`Authorization` 헤더를 사용하므로 브라우저에서 단순히 함께 적용할 수 없습니다.

### 설정

| 환경 변수 | 설명 | 기본값 |
|----------|------|--------|
| `HTTP_PORT` | 웹 UI 및 API 포트 | 8080 |
| `ADMIN_TOKEN` | `/api/*` 접근에 필요한 bearer 토큰. 미설정 시 앱 수준 인증 없음 | - |

### 제한사항

- 체크 이력은 메모리에만 보관되어 파드 재시작 시 소실됩니다. 재시작 후에도 남는
  업데이트 이력은 `watch-cluster.io/last-update`, `watch-cluster.io/change`
  annotation과 Kubernetes 이벤트에 있습니다.
- UI가 Kubernetes 이벤트를 읽으려면 `k8s/rbac.yaml`에 추가된 `list` 권한이
  필요합니다. 업그레이드 시 다시 적용하십시오.
- 워커 상태는 프로세스별로 존재하므로 replica는 1개로 운영해야 합니다 (기본
  Deployment는 이미 `replicas: 1` + `Recreate` 전략입니다).

## 고급 사용법

### 업데이트 후 상태 확인

업데이트가 수행되면 deployment에 다음 annotation이 추가됩니다:

```yaml
watch-cluster.io/last-update: "2024-01-01T12:00:00+09:00"  # ISO 8601 형식 (로컬 시간대)
watch-cluster.io/change: "myapp:1.0.0 -> myapp:1.0.1"      # 이미지 변경 내역
```

### 웹훅 설정

watch-cluster는 다음 이벤트에 대해 웹훅을 전송할 수 있습니다:

#### 웹훅 환경변수

ConfigMap을 수정하여 웹훅을 설정할 수 있습니다:

```bash
kubectl edit configmap watch-cluster-config -n watch-cluster
```

| 환경변수 | 설명 | 필수 | 기본값 |
|----------|------|------|--------|
| `WEBHOOK_URL` | 웹훅 요청을 보낼 URL | 아니오 | - |
| `WEBHOOK_ENABLE_DEPLOYMENT_DETECTED` | deployment 감지 이벤트 웹훅 활성화 여부 | 아니오 | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_STARTED` | 이미지 롤아웃 시작 이벤트 웹훅 활성화 여부 | 아니오 | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_COMPLETED` | 이미지 롤아웃 완료 이벤트 웹훅 활성화 여부 | 아니오 | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_FAILED` | 이미지 롤아웃 실패 이벤트 웹훅 활성화 여부 | 아니오 | false |
| `WEBHOOK_HEADERS` | 웹훅 요청에 포함할 헤더 (`key1=value1,key2=value2` 형식) | 아니오 | - |
| `WEBHOOK_TIMEOUT` | 웹훅 요청 타임아웃 (밀리초) | 아니오 | 10000 |
| `WEBHOOK_RETRY_COUNT` | 웹훅 요청 재시도 횟수 | 아니오 | 3 |

#### 웹훅 이벤트 타입

- `DEPLOYMENT_DETECTED`: 새로운 deployment가 감지되거나 기존 deployment가 업데이트될 때
- `IMAGE_ROLLOUT_STARTED`: 이미지 롤아웃이 시작될 때
- `IMAGE_ROLLOUT_COMPLETED`: 이미지 롤아웃이 성공적으로 완료될 때
- `IMAGE_ROLLOUT_FAILED`: 이미지 롤아웃이 실패할 때

#### 웹훅 페이로드 예시

```json
{
  "eventType": "IMAGE_ROLLOUT_COMPLETED",
  "timestamp": "2024-01-01T12:00:00Z",
  "deployment": {
    "namespace": "default",
    "name": "my-app",
    "image": "myapp:1.0.1"
  },
  "details": {
    "rolloutDuration": "45000ms"
  }
}
```

### 로그 레벨 설정

환경변수를 사용하여 각 모듈별 로그 레벨을 설정할 수 있습니다:

| 환경변수 | 설명 | 기본값 |
|----------|------|--------|
| `LOG_LEVEL` | 루트 로그 레벨 | INFO |
| `LOG_LEVEL_WATCHCLUSTER` | watch-cluster 모듈 로그 레벨 | INFO |
| `LOG_LEVEL_CONTROLLER` | Controller 모듈 로그 레벨 | INFO |
| `LOG_LEVEL_SERVICE` | Service 모듈 로그 레벨 | INFO |
| `LOG_LEVEL_UTIL` | Utility 모듈 로그 레벨 | INFO |
| `LOG_LEVEL_KUBERNETES` | Kubernetes 클라이언트 로그 레벨 | INFO |
| `LOG_LEVEL_DOCKER` | Docker 클라이언트 로그 레벨 | INFO |

사용 가능한 로그 레벨: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`

Deployment에서 설정 예시:
```yaml
env:
- name: LOG_LEVEL
  value: "INFO"
- name: LOG_LEVEL_SERVICE
  value: "DEBUG"
- name: LOG_LEVEL_KUBERNETES
  value: "WARN"
```

### 모니터링 및 디버깅

```bash
# 실시간 로그 확인
kubectl logs -n watch-cluster -l app=watch-cluster -f

# 특정 deployment의 annotation 확인
kubectl get deployment my-app -o jsonpath='{.metadata.annotations}'

# ConfigMap 확인
kubectl get configmap watch-cluster-config -n watch-cluster -o yaml

# 업데이트 이벤트 확인
kubectl get events --field-selector reason=ImageUpdated
```

## 실제 사용 시나리오

### 1. 개발 환경 - 빠른 업데이트
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "*/5 * * * *"      # 5분마다
  watch-cluster.io/strategy: "latest"
```

### 2. 스테이징 환경 - 일일 업데이트
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "0 2 * * *"       # 매일 새벽 2시
  watch-cluster.io/strategy: "version"
```

### 3. 프로덕션 환경 - 수동 제어
```yaml
annotations:
  watch-cluster.io/enabled: "false"        # 필요시만 활성화
  watch-cluster.io/cron: "0 3 * * SUN"     # 일요일 새벽 3시
  watch-cluster.io/strategy: "version"
```

## 제한사항

- 현재는 각 Deployment의 첫 번째 컨테이너만 업데이트합니다
- Private 레지스트리 인증은 Kubernetes imagePullSecret을 통해 지원됩니다
- 롤백 기능은 포함되어 있지 않습니다
- 웹 UI의 체크 이력은 메모리에만 있어 재시작 시 소실됩니다
- Docker 데몬에 직접 접근하지 않고 레지스트리 API를 통해 이미지 정보를 확인합니다

## 문제 해결

### Deployment가 시작되지 않는 경우
```bash
kubectl describe deployment -n watch-cluster watch-cluster
kubectl logs -n watch-cluster -l app=watch-cluster
```

### 업데이트가 수행되지 않는 경우
1. Annotation이 올바르게 설정되었는지 확인
2. Cron 표현식이 유효한지 확인
3. 이미지 레지스트리 접근 권한 확인
4. 로그에서 오류 메시지 확인

## 라이선스

MIT License
