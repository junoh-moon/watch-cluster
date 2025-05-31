# Watch-Cluster

An automatic container image update tool for Kubernetes. Provides functionality similar to Docker's Watchtower in Kubernetes environments.

## Key Features

- Annotation-based selective monitoring
- Automatic updates based on semantic versioning
- Change detection and updates for latest tag images
- Digest-based update detection for arbitrary tags (stable, release-candidate, etc.)
- Flexible scheduling using Cron expressions
- Deployed as a Deployment running in the cluster
- Waits for rolling update completion
- Event notifications via webhooks (deployment detection, image rollout status)

## Getting Started

### Prerequisites

- Kubernetes cluster (v1.20+)
- kubectl installed with cluster access
- Docker (for image building)
- imagePullSecret configuration required for private registries

### Kubernetes Manifest Files

The `k8s/` directory contains the following files:

- `namespace.yaml`: Creates the watch-cluster namespace
- `rbac.yaml`: ServiceAccount, ClusterRole, ClusterRoleBinding configuration
- `configmap.yaml`: ConfigMap for webhook settings
- `deployment.yaml`: watch-cluster application deployment configuration
- `example-deployment.yaml`: Example application for testing (version tag)
- `example-deployment-stable.yaml`: Example using arbitrary tags (stable, custom tag)

### Installation

```bash
# 1. Clone the source
git clone https://github.com/your-org/watch-cluster.git
cd watch-cluster

# 2. Build Docker image
docker build -t watch-cluster:latest .

# 3. Push image to registry (optional)
docker tag watch-cluster:latest your-registry/watch-cluster:latest
docker push your-registry/watch-cluster:latest

# 4. Deploy to Kubernetes
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
```

### Verify Installation

```bash
# Check Deployment status
kubectl get deployment -n watch-cluster watch-cluster

# Check logs
kubectl logs -n watch-cluster -l app=watch-cluster
```

### Deploy Example Application

To test watch-cluster's behavior, you can deploy the example application:

```bash
# Deploy example application
kubectl apply -f k8s/example-deployment.yaml

# Check example application status
kubectl get deployment example-app
kubectl logs -n watch-cluster -l app=watch-cluster
```

## Usage

### Basic Usage Example

Enable automatic updates by adding annotations to your Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  annotations:
    watch-cluster.io/enabled: "true"
    watch-cluster.io/cron: "0 */30 * * * ?"  # Check every 30 minutes
    watch-cluster.io/strategy: "version"      # Version-based updates
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

### Annotation Reference

| Annotation | Description | Required | Default |
|------------|-------------|----------|---------|
| `watch-cluster.io/enabled` | Enable monitoring | Yes | - |
| `watch-cluster.io/cron` | Update check interval (Quartz cron) | No | `0 */5 * * * ?` |
| `watch-cluster.io/strategy` | Update strategy (`version`, `version-lock-major`, `latest`) | No | `version` |

### Update Strategies

#### 1. Version Strategy
Suitable for images using semantic versioning:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "version"
```

Supported version formats:
- `1.0.0`, `1.0.1`, `1.1.0`
- `v1.0.0`, `v1.0.1`, `v1.1.0`
- `1.0.0-beta`, `1.0.0-rc1`

#### 2. Version Lock Major Strategy
Locks the major version and only updates minor/patch versions:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "version-lock-major"
```

Examples:
- If current version is `v1.0.0`, it will update to `v1.1.0` or `v1.0.1` but not to `v2.0.0`
- If current version is `v0.5.0`, it will update to `v0.6.0` or `v0.5.1` but not to `v1.0.0`

#### 3. Latest Strategy
Suitable for images using the `latest` tag or non-version arbitrary tags:

```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/strategy: "latest"
```

Detects actual changes by comparing image digests. Supports tags like:
- `latest` - Most recent build
- `stable` - Stable version
- `release-candidate` - Release candidate
- `release-openvino` - Framework-specific release
- `dev`, `nightly`, `edge` - Development/experimental versions
- Any other non-version format tags

**Note**: Version-formatted tags (e.g., `v1.0.0`, `1.2.3`) should use the Version strategy.

### Cron Expression Examples

| Expression | Description |
|------------|-------------|
| `0 */5 * * * ?` | Every 5 minutes |
| `0 0 * * * ?` | Every hour on the hour |
| `0 0 2 * * ?` | Daily at 2 AM |
| `0 0 9-17 * * MON-FRI` | Every hour 9 AM-5 PM on weekdays |
| `0 0 0 * * MON` | Every Monday at midnight |
| `0 0 0 1 * ?` | First day of every month at midnight |

## Advanced Usage

### Monitor Specific Namespaces Only

Limit namespaces via Deployment environment variables:

```yaml
env:
- name: WATCH_NAMESPACES
  value: "default,production"
```

### Check Status After Update

After an update is performed, the following annotations are added to the deployment:

```yaml
watch-cluster.io/last-update: "2024-01-01T12:00:00+09:00"  # ISO 8601 format (local timezone)
watch-cluster.io/last-update-image: "myapp:1.0.1"
watch-cluster.io/last-update-from-digest: "sha256:abc123..."  # Digest before update
watch-cluster.io/last-update-to-digest: "sha256:def456..."    # Digest after update
```

Especially when using the `latest` tag, digest information confirms if the actual image has changed.

### Webhook Configuration

watch-cluster can send webhooks for the following events:

#### Webhook Environment Variables

Configure webhooks by modifying the ConfigMap:

```bash
kubectl edit configmap watch-cluster-config -n watch-cluster
```

| Environment Variable | Description | Required | Default |
|---------------------|-------------|----------|---------|
| `WEBHOOK_URL` | URL to send webhook requests | No | - |
| `WEBHOOK_ENABLE_DEPLOYMENT_DETECTED` | Enable webhook for deployment detection events | No | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_STARTED` | Enable webhook for image rollout start events | No | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_COMPLETED` | Enable webhook for image rollout completion events | No | false |
| `WEBHOOK_ENABLE_IMAGE_ROLLOUT_FAILED` | Enable webhook for image rollout failure events | No | false |
| `WEBHOOK_HEADERS` | Headers to include in webhook requests (`key1=value1,key2=value2` format) | No | - |
| `WEBHOOK_TIMEOUT` | Webhook request timeout (milliseconds) | No | 10000 |
| `WEBHOOK_RETRY_COUNT` | Number of webhook request retries | No | 3 |

#### Webhook Event Types

- `DEPLOYMENT_DETECTED`: When a new deployment is detected or an existing deployment is updated
- `IMAGE_ROLLOUT_STARTED`: When an image rollout starts
- `IMAGE_ROLLOUT_COMPLETED`: When an image rollout completes successfully
- `IMAGE_ROLLOUT_FAILED`: When an image rollout fails

#### Webhook Payload Example

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

### Monitoring and Debugging

```bash
# View real-time logs
kubectl logs -n watch-cluster -l app=watch-cluster -f

# Check annotations for a specific deployment
kubectl get deployment my-app -o jsonpath='{.metadata.annotations}'

# Check ConfigMap
kubectl get configmap watch-cluster-config -n watch-cluster -o yaml

# Check update events
kubectl get events --field-selector reason=ImageUpdated
```

## Real-World Scenarios

### 1. Development Environment - Rapid Updates
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "0 */5 * * * ?"    # Every 5 minutes
  watch-cluster.io/strategy: "latest"
```

### 2. Staging Environment - Daily Updates
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "0 0 2 * * ?"     # Daily at 2 AM
  watch-cluster.io/strategy: "version"
```

### 3. Production Environment - Manual Control
```yaml
annotations:
  watch-cluster.io/enabled: "false"        # Enable only when needed
  watch-cluster.io/cron: "0 0 3 * * SUN"   # Sunday at 3 AM
  watch-cluster.io/strategy: "version"
```

## Limitations

- Currently only updates the first container in each Deployment
- Private registry authentication is supported through Kubernetes imagePullSecret
- Rollback functionality is not included
- Checks image information through registry API without direct Docker daemon access

## Troubleshooting

### If Deployment Doesn't Start
```bash
kubectl describe deployment -n watch-cluster watch-cluster
kubectl logs -n watch-cluster -l app=watch-cluster
```

### If Updates Are Not Being Performed
1. Verify annotations are set correctly
2. Check if cron expression is valid
3. Verify image registry access permissions
4. Check logs for error messages

## License

MIT License