# Watch-Cluster

Kubernetes용 자동 컨테이너 이미지 업데이트 도구입니다. Docker의 Watchtower와 유사한 기능을 Kubernetes 환경에서 제공합니다.

## 주요 기능

- Annotation 기반 선택적 모니터링
- 시맨틱 버저닝 기반 자동 업데이트
- Latest 태그 이미지의 변경 감지 및 업데이트
- Cron 표현식을 사용한 유연한 스케줄링
- Deployment로 배포되어 클러스터에서 실행
- 롤링 업데이트 완료까지 대기
- 웹훅을 통한 이벤트 알림 (deployment 감지, 이미지 롤아웃 상태)

## 시작하기

### 사전 요구사항

- Kubernetes 클러스터 (v1.20+)
- kubectl 설치 및 클러스터 접근 권한
- Docker (이미지 빌드용)

### Kubernetes 매니페스트 파일

`k8s/` 디렉토리에는 다음 파일들이 포함되어 있습니다:

- `namespace.yaml`: watch-cluster 네임스페이스 생성
- `rbac.yaml`: ServiceAccount, ClusterRole, ClusterRoleBinding 설정
- `configmap.yaml`: 웹훅 설정을 위한 ConfigMap
- `deployment.yaml`: watch-cluster 애플리케이션 배포 설정
- `example-deployment.yaml`: 테스트용 예시 애플리케이션

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
```

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
    watch-cluster.io/cron: "0 */30 * * * ?"  # 30분마다 확인
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
| `watch-cluster.io/cron` | 업데이트 확인 주기 (Quartz cron) | 아니오 | `0 */5 * * * ?` |
| `watch-cluster.io/strategy` | 업데이트 전략 (`version`, `version-lock-major`, `latest`) | 아니오 | `version` |

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
`latest` 태그를 사용하는 이미지에 적합합니다:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "latest"
```

이미지 다이제스트를 비교하여 실제 변경 사항을 감지합니다.

### Cron 표현식 예시

| 표현식 | 설명 |
|--------|------|
| `0 */5 * * * ?` | 5분마다 |
| `0 0 * * * ?` | 매시간 정각 |
| `0 0 2 * * ?` | 매일 오전 2시 |
| `0 0 9-17 * * MON-FRI` | 평일 9시-17시 매시간 |
| `0 0 0 * * MON` | 매주 월요일 자정 |
| `0 0 0 1 * ?` | 매월 1일 자정 |

## 고급 사용법

### 특정 네임스페이스만 모니터링

Deployment 환경변수로 네임스페이스를 제한할 수 있습니다:

```yaml
env:
- name: WATCH_NAMESPACES
  value: "default,production"
```

### 업데이트 후 상태 확인

업데이트가 수행되면 deployment에 다음 annotation이 추가됩니다:

```yaml
watch-cluster.io/last-update: "1704067200000"
watch-cluster.io/last-update-image: "myapp:1.0.1"
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
  watch-cluster.io/cron: "0 */5 * * * ?"    # 5분마다
  watch-cluster.io/strategy: "latest"
```

### 2. 스테이징 환경 - 일일 업데이트
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "0 0 2 * * ?"     # 매일 새벽 2시
  watch-cluster.io/strategy: "version"
```

### 3. 프로덕션 환경 - 수동 제어
```yaml
annotations:
  watch-cluster.io/enabled: "false"        # 필요시만 활성화
  watch-cluster.io/cron: "0 0 3 * * SUN"   # 일요일 새벽 3시
  watch-cluster.io/strategy: "version"
```

## 제한사항

- 현재는 각 Deployment의 첫 번째 컨테이너만 업데이트합니다
- Private 레지스트리 인증은 추가 설정이 필요합니다
- 롤백 기능은 포함되어 있지 않습니다

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