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
- Built-in web UI for viewing watched apps and triggering updates

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
- `service.yaml`: ClusterIP Service exposing the web UI
- `ingress.yaml`: HAProxy Ingress with Basic Auth, TLS, and Flame integration
- `create_account.example.sh`: Template for creating or updating an admin account
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

# 5. Expose the web UI (optional)
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml   # edit the host first
```

### Upgrading Existing Installations

When upgrading an existing watch-cluster installation, re-apply the RBAC manifest before relying on update checks:

```bash
kubectl apply -f k8s/rbac.yaml
```

The `latest` strategy resolves the node platform before comparing image digests, so the watch-cluster ServiceAccount needs `get` access to core `nodes`. Without this permission, platform digest lookup can fail and arbitrary-tag updates may be skipped.

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
    watch-cluster.io/cron: "*/30 * * * *"    # Check every 30 minutes
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
| `watch-cluster.io/cron` | Update check interval (Unix cron) | No | `*/5 * * * *` |
| `watch-cluster.io/strategy` | Update strategy (`version`, `version-lock-major`, `latest`) | No | `version` |
| `watch-cluster.io/check-now` | Trigger one immediate update check, then remove this annotation | No | - |

### Manual Check Trigger

To run the same check that would normally happen on the next cron execution, add the `watch-cluster.io/check-now` annotation to a monitored Deployment:

```bash
kubectl annotate deployment -n my-namespace my-app watch-cluster.io/check-now=true --overwrite
```

watch-cluster removes the annotation after it observes the request, runs one check using the Deployment's existing strategy, and records Kubernetes Events for the request and result.

You can inspect the audit trail with:

```bash
kubectl get events -n my-namespace --sort-by=.metadata.creationTimestamp
```

### Minimum Release Age

Delay updates until the selected Docker Hub image has been pushed for at least the configured duration:

```yaml
annotations:
  watch-cluster.io/minimum-release-age: "3d"
```

Use a positive integer followed by `h`, `d`, or `w` (for example `12h`, `3d`, `3w`); a day is 24 hours and a week is 7 days.
An absent annotation or `0` disables the delay. Invalid values, including fractions, compound durations, whitespace, and overflow,
are ignored with a warning. Configure this annotation on the Deployment; the web UI displays it and the latest check result.

Only the latest candidate allowed by the existing update strategy is evaluated. A newer candidate replaces a waiting one;
older eligible versions are not used as a fallback. The registry's last push time is used, never the first discovery time,
image build time, or project release time. Tag/index candidates use `tag_last_pushed`; platform image candidates use the
matching digest's `images[].last_pushed`. These are last-push timestamps, not guaranteed first-publication timestamps.

Missing timestamps, digest mismatches, unsupported registries, and lookup failures hold the update and are retried at the next check.
The UI shows `유예 대기` (waiting) or `공개 시각 확인 불가` (time unavailable); check history includes the candidate and, when known,
its eligible time. Scheduled updates run on the next cron check after eligibility. `check-now` respects the same gate.
No waiting timer is persisted: checks after a restart fetch the registry timestamp again.

When the gate allows an update, the verified digest is pinned alongside the tag, including for the `latest` strategy,
so a tag moving between the check and the image pull cannot bypass the delay. Unset/zero settings preserve existing behavior.
With the `latest` strategy, this currently pins the observed platform image. Use this gate only when the Deployment's eligible
nodes share that architecture; preserving multi-architecture index selection is deferred.

Set `MINIMUM_RELEASE_AGE_EXCLUDED_REGISTRIES` to comma-separated exact registry hosts to always bypass the gate for those hosts.
The supplied ConfigMap excludes `hub.sixtyfive.me`; the application has no built-in excluded hosts. Apply both the ConfigMap
and Deployment configuration when upgrading to enable this environment setting. Other non-Docker-Hub registries with a positive
minimum age remain on hold until the setting is disabled or the registry is explicitly excluded.

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

Tags such as `12.0ubu2604-ls48` compare using only their leading numeric version (`12.0`), retaining the packaging suffix when updating the image. Changes to the suffix alone do not trigger an update. `rc`, `alpha`, and `beta` prereleases are excluded, including attached forms such as `12.0rc1`.

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

Uses 5-field Unix cron: `minute hour day-of-month month day-of-week`.

| Expression | Description |
|------------|-------------|
| `*/5 * * * *` | Every 5 minutes |
| `0 * * * *` | Every hour on the hour |
| `0 2 * * *` | Daily at 2 AM |
| `0 9-17 * * MON-FRI` | Every hour 9 AM-5 PM on weekdays |
| `0 0 * * MON` | Every Monday at midnight |
| `0 0 1 * *` | First day of every month at midnight |

`*/N` is not a fixed-interval timer. It matches values stepped by `N` within that field. For example, `*/7 * * * *` runs at minutes `0, 7, 14, ..., 56` every hour.

## Web UI

watch-cluster serves an admin UI and JSON API on port 8080 from the same
process as the controller. Schedule state and check history live only in the
controller's memory, so co-locating them is what lets the UI show more than
the annotations already say.

```bash
kubectl apply -f k8s/service.yaml

# Try it without an Ingress
kubectl port-forward -n watch-cluster svc/watch-cluster 8080:80
open http://localhost:8080
```

The UI lists every watched Deployment with its strategy, schedule, next check
time, and last check result; expands a row to show check history, Kubernetes
Events, and inline cron/strategy editing; triggers a manual check; and starts
watching a new app by picking from the cluster's unwatched Deployments.

### API Reference

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/apps` | Watched deployments with schedule and check state |
| `GET` | `/api/apps/{ns}/{name}` | Detail with in-memory check history and Kubernetes Events |
| `POST` | `/api/apps/{ns}/{name}/check` | Trigger one check (202; runs asynchronously) |
| `PUT` | `/api/apps/{ns}/{name}/watch` | Start watching or update `{"cron": "...", "strategy": "..."}`; omitted fields keep their current value |
| `DELETE` | `/api/apps/{ns}/{name}/watch` | Stop watching (sets `enabled: "false"`, keeps cron/strategy) |
| `GET` | `/api/deployments` | Deployments not yet watched, as candidates |
| `GET` | `/healthz`, `/readyz` | Health probes (never authenticated) |

Every write goes through the same `watch-cluster.io/*` annotations that
`kubectl annotate` sets, so the UI, the API, and the CLI share one code path.
Invalid cron expressions and unknown strategies are rejected with 400 rather
than silently falling back to a default.

### Authentication

`k8s/ingress.yaml` exposes the UI and `/api/*` at `https://watch.sixtyfive.me`
with HAProxy Basic Auth. TLS uses cert-manager's `letsencrypt` ClusterIssuer.
Use HAProxy Kubernetes Ingress Controller 3.2.13 or later in the 3.2 series,
which redirects HTTP to HTTPS before requesting Basic Auth credentials.

Create the authentication Secret before applying the Ingress. The script requires
`kubectl`, `openssl`, and `jq`, and the `watch-cluster` namespace must exist:

```bash
cp -n k8s/create_account.example.sh k8s/create_account.sh
# Set ACCOUNT_ID and ACCOUNT_PASSWORD in k8s/create_account.sh, then run:
sh k8s/create_account.sh
```

The local script is gitignored; keep credentials out of the tracked example.
It stores password hashes in `watch-cluster-basic-auth`, preserves other accounts,
and makes no change when rerun with the same ID/password. To change a password,
edit the local script and rerun it. Changing the ID adds another account.

Basic Auth protects requests through the Ingress. Direct in-cluster Service
access is not protected by it; this manifest does not include a NetworkPolicy
or authentication-failure throttling.

The application also supports optional `ADMIN_TOKEN` authentication for `/api/*`.
When enabled, the UI prompts for a Bearer token and stores it in `localStorage`.
Leave it unset for the Ingress Basic Auth setup described above: both mechanisms
use the `Authorization` header and cannot simply be combined in the browser.

### Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `HTTP_PORT` | Port for the web UI and API | 8080 |
| `ADMIN_TOKEN` | Bearer token required for `/api/*`; unset means no app-level auth | - |

### Limitations

- Check history is held in memory and is lost when the pod restarts. Update
  history that survives restarts lives in the `watch-cluster.io/last-update`
  and `watch-cluster.io/change` annotations and in Kubernetes Events.
- The UI reads Kubernetes Events, which requires the `list` verb added in
  `k8s/rbac.yaml`. Re-apply it when upgrading.
- Worker state is per-process, so run a single replica (the shipped
  Deployment already uses `replicas: 1` with the `Recreate` strategy).

## Advanced Usage

### Check Status After Update

After an update is performed, the following annotations are added to the deployment:

```yaml
watch-cluster.io/last-update: "2024-01-01T12:00:00+09:00"  # ISO 8601 format (local timezone)
watch-cluster.io/change: "myapp:1.0.0 -> myapp:1.0.1"      # Image change details
```

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

### Log Level Configuration

Configure logging levels for different modules using environment variables:

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `LOG_LEVEL` | Root log level | INFO |
| `LOG_LEVEL_WATCHCLUSTER` | watch-cluster module log level | INFO |
| `LOG_LEVEL_CONTROLLER` | Controller module log level | INFO |
| `LOG_LEVEL_SERVICE` | Service module log level | INFO |
| `LOG_LEVEL_UTIL` | Utility module log level | INFO |
| `LOG_LEVEL_KUBERNETES` | Kubernetes client log level | INFO |
| `LOG_LEVEL_DOCKER` | Docker client log level | INFO |

Available log levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`

Example configuration in deployment:
```yaml
env:
- name: LOG_LEVEL
  value: "INFO"
- name: LOG_LEVEL_SERVICE
  value: "DEBUG"
- name: LOG_LEVEL_KUBERNETES
  value: "WARN"
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
  watch-cluster.io/cron: "*/5 * * * *"      # Every 5 minutes
  watch-cluster.io/strategy: "latest"
```

### 2. Staging Environment - Daily Updates
```yaml
annotations:
  watch-cluster.io/enabled: "true"
  watch-cluster.io/cron: "0 2 * * *"       # Daily at 2 AM
  watch-cluster.io/strategy: "version"
```

### 3. Production Environment - Manual Control
```yaml
annotations:
  watch-cluster.io/enabled: "false"        # Enable only when needed
  watch-cluster.io/cron: "0 3 * * SUN"     # Sunday at 3 AM
  watch-cluster.io/strategy: "version"
```

## Limitations

- Currently only updates the first container in each Deployment
- Private registry authentication is supported through Kubernetes imagePullSecret
- Rollback functionality is not included
- Web UI check history is in-memory only and does not survive a restart
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
