# Minimum release age: 사용 이미지와 시각 조회 조사

조사일: 2026-09-16 (KST). 기능은 아직 구현하지 않았습니다.

조사 후 결정: 사용자가 직접 개발·사용하는 `hub.sixtyfive.me` 이미지는 유예 기간을 항상 `0`으로 취급하여 시각 조회와 유예 판단을 건너뜁니다. 아래의 API 조사 결과는 그대로 유효하지만, 이 개인 레지스트리를 위한 시각 기록 시스템은 이번 기능에 필요하지 않습니다.

추가 조사: GHCR 대상 4개 모두 현재 사용하는 태그의 공식 Docker Hub 배포를 확인했습니다. 아래의 대체 레지스트리 조사 결과를 참고하십시오. 전환하면 현재 관리 대상에 대한 GHCR 시각 API 인증을 피할 수 있으나, 실제 배포 전환은 아직 수행하지 않았습니다.

## 결론

현재 watch-cluster 관리 대상으로 설정된 Deployment 21개의 첫 번째 컨테이너를 조사했습니다. Docker Hub 15개는 push 시각을 실제로 조회했습니다. GHCR 4개는 패키지 버전 시각 API가 있지만 현재 인증 권한으로는 검증하지 못했습니다. 개인 레지스트리 2개는 인증 후 manifest 조회에 성공했으나 공개 시각을 얻지 못했습니다.

**모든 레지스트리에 공통으로 적용할 수 있는 digest별 최초 공개 시각 API는 확인되지 않았습니다.** Docker Hub의 마지막 push 시각과 GHCR의 패키지 버전 생성 시각도 엄밀한 최초 공개 시각과 동일하다고 단정할 수 없습니다. 최초 발견 시각이나 이미지 빌드 시각으로 자동 대체하지 않습니다.

## 조사 범위와 완료 내역

- [x] 현재 컨텍스트 `default`에서 Deployment·StatefulSet·DaemonSet 56개를 읽고 관리 대상 21개를 구분했습니다.
- [x] 레지스트리별 API와 실제 응답을 확인했습니다. GHCR은 권한 부족이라는 검증 한계를 기록했습니다.
- [x] digest 변경과 멀티 아키텍처 이미지의 시각 의미를 검토했습니다.
- [x] 결과와 남은 정책 결정을 기록했습니다.

현재 코드의 `WatchController`는 `containers[0]`만 관리하므로, `dropbox`의 `debian` 보조 컨테이너는 21개 집계에 포함하지 않았습니다. 노드는 `linux/amd64` 1개입니다. 이 목록은 관리 annotation 기준이며 개별 worker의 실행 상태까지 검증한 것은 아닙니다.

관리하지 않는 35개 워크로드에는 Quay, registry.k8s.io, docker.elastic.co 등도 있으나, 이번 시각 API 검증은 현재 관리 대상의 세 레지스트리에 집중했습니다. Job·CronJob은 이번 목록 조사 범위에 포함하지 않았습니다.

## 관리 대상 목록

가독성을 위해 아래 표에서 Deployment에 고정된 `@sha256:…`는 생략했습니다. Docker Hub 시각 검증은 표에 기재한 태그가 조사 당시 가리키는 이미지에 대해 수행했으며, 모든 서비스의 다음 업데이트 후보를 계산한 것은 아닙니다.

| Namespace / Deployment | 이미지 | 전략 |
|---|---|---|
| default / cv-server | `nginx:alpine` | latest |
| default / docker-registry | `registry:3.1.1` | version |
| default / docker-registry-web | `joxit/docker-registry-ui:latest` | latest |
| default / dropbox | `maestraldbx/maestral:latest` | latest |
| default / flame | `pawelmalak/flame:latest` | latest |
| default / jellyfin | `linuxserver/jellyfin:12.1ubu2604-ls49` | version |
| default / kavita | `ghcr.io/kareadita/kavita:0.9.1` | version |
| default / navidrome | `deluan/navidrome:0.64.0` | version |
| default / nextcloud | `linuxserver/nextcloud:31.0.14-previous` | version-lock-major |
| default / ntfy | `binwiederhier/ntfy` | latest |
| default / onlyoffice | `onlyoffice/documentserver:9.4` | version |
| default / reloader-reloader | `ghcr.io/stakater/reloader:v1.4.2` | version |
| default / syncthing | `syncthing/syncthing:2.1.5` | version |
| default / torrent | `linuxserver/qbittorrent` | latest |
| default / transfer | `dutchcoders/transfer.sh:latest` | latest |
| default / transfer-shortener | `hub.sixtyfive.me/transfer-shortener:latest` | latest |
| default / vaultwarden | `vaultwarden/server:1.37.3` | version |
| homeassistant / homeassistant | `homeassistant/home-assistant:2026.9.2` | version |
| immich / immich-machine-learning | `ghcr.io/immich-app/immich-machine-learning:release-openvino` | latest |
| immich / immich-server | `ghcr.io/immich-app/immich-server:v3.2.2` | version |
| watch-cluster / watch-cluster | `hub.sixtyfive.me/watch-cluster:v0.50.46` | version |

## Docker Hub: 15개 모두 조회 성공

사용한 공개 API:

```text
GET https://hub.docker.com/v2/namespaces/{namespace}/repositories/{repository}/tags/{tag}
```

15개 모두 인증 없이 HTTP 200을 반환했고, 태그의 `digest`, `tag_last_pushed`, `images[]` 안의 linux/amd64 `digest`와 `last_pushed`를 확인했습니다. 아래 시각은 모두 UTC입니다.

| 저장소:태그 | 태그 마지막 push | amd64 이미지 마지막 push |
|---|---|---|
| `library/nginx:alpine` | 2026-09-16T07:52:32.104701Z | 2026-09-16T01:50:42.323893592Z |
| `library/registry:3.1.1` | 2026-06-23T14:38:22.15794Z | 2026-06-22T20:38:19.930326904Z |
| `joxit/docker-registry-ui:latest` | 2026-01-19T23:29:39.688313Z | 2026-01-19T23:29:34.181016194Z |
| `maestraldbx/maestral:latest` | 2025-11-04T22:31:00.356163Z | 2025-11-04T22:30:51.581082654Z |
| `pawelmalak/flame:latest` | 2026-04-24T17:20:52.866726Z | 2026-04-24T17:20:52.600856564Z |
| `linuxserver/jellyfin:12.1ubu2604-ls49` | 2026-09-15T05:55:22.684187Z | 2026-09-15T05:52:52.572555977Z |
| `deluan/navidrome:0.64.0` | 2026-09-12T18:26:30.94135Z | 2026-09-12T18:25:27.275029656Z |
| `linuxserver/nextcloud:31.0.14-previous` | 2026-02-17T12:21:15.299987Z | 2026-02-17T12:19:45.686365576Z |
| `binwiederhier/ntfy:latest` | 2026-08-27T20:26:41.91802Z | 2026-08-27T20:26:35.302989721Z |
| `onlyoffice/documentserver:9.4` | 2026-05-19T09:13:22.418708Z | 2026-05-19T09:13:14.663998343Z |
| `syncthing/syncthing:2.1.5` | 2026-09-08T07:27:01.488021Z | 2026-09-08T07:26:40.60611798Z |
| `linuxserver/qbittorrent:latest` | 2026-09-13T12:27:44.993257Z | 2026-09-13T12:25:48.803550685Z |
| `dutchcoders/transfer.sh:latest` | 2026-09-16T00:54:05.539839Z | 2026-09-16T00:54:02.037671863Z |
| `vaultwarden/server:1.37.3` | 2026-09-13T14:55:33.741002Z | 2026-09-13T14:53:29.896685258Z |
| `homeassistant/home-assistant:2026.9.2` | 2026-09-11T19:53:34.012236Z | 2026-09-11T19:53:32.937241924Z |

Docker 공식 OpenAPI는 `images[].last_pushed`와 `tag_last_pushed`를 모두 마지막 push 시각으로 설명하며, null도 허용합니다. 따라서 이번 15건의 성공을 모든 이미지와 향후 응답에 대한 보장으로 취급하지 마십시오. `last_updated`는 마지막 갱신 시각이므로 공개 시각의 우선 출처로 사용하지 않는 편이 좋겠습니다.

실제 `nginx:alpine`에서는 태그 push가 `07:52 UTC`, amd64 이미지 push가 `01:50 UTC`로 약 6시간 차이가 났습니다. 다른 플랫폼의 이미지와 provenance용 `unknown/unknown` 항목도 함께 반환됩니다. 첫 번째 배열 원소를 고르지 말고 대상 OS·architecture·variant와 digest를 대조해야 합니다.

현재 Docker Hub 조회 코드는 플랫폼이 주어진 경우 플랫폼 digest를, 그렇지 않은 경우 최상위 digest를 반환합니다. 시각 조회에서도 같은 구분을 유지해야 합니다.

- 플랫폼 이미지 digest를 후보로 선택했다면 일치하는 `images[].digest`의 `last_pushed`가 조회 후보입니다.
- 최상위 인덱스 digest를 후보로 선택했다면 현재 태그의 `digest` 일치를 확인하고 `tag_last_pushed`를 사용하는 방안을 검토할 수 있습니다. 이 값은 해당 digest의 최초 공개 시각을 보장하지 않습니다.
- 태그 응답은 현재 가리키는 이미지에 관한 정보입니다. 태그가 바뀐 뒤 이전 digest의 시각이라고 재사용해서는 안 됩니다.
- 같은 digest를 재차 push하거나 태그를 다시 붙이는 경우 시각이 어떻게 바뀌는지는 이번 읽기 전용 조사로 재현하지 않았습니다. 최초 공개 시각 대신 마지막 push를 사용하면 추가 대기가 생길 수 있다는 점을 정책으로 받아들일지 결정해야 합니다.

`registry:3.1.1`은 Deployment에 고정된 digest가 `sha256:85347ed2…`인 반면, 조사 당시 태그 API의 digest는 `sha256:1be55279…`였습니다. 나머지 Docker Hub의 digest 고정 6개는 최상위 digest가 일치했습니다. 버전 태그도 현재 이미지와 태그 대상이 다를 수 있으므로 시각과 digest를 묶어야 합니다. 이 차이를 곧바로 새 버전 업데이트 대상으로 삼을지는 별도 정책이며, 현재 version 전략은 더 높은 버전 번호를 선택합니다.

근거: [Docker Hub API](https://docs.docker.com/reference/api/hub/latest/), [공식 OpenAPI 스키마](https://docs.docker.com/reference/api/hub/latest.yaml), [nginx:alpine 실제 조회 endpoint](https://hub.docker.com/v2/namespaces/library/repositories/nginx/tags/alpine).

## GHCR: 시각 API는 있으나 실제 값 검증은 권한 부족

대상은 Kavita, Reloader, Immich server, Immich machine learning의 4개입니다.

GitHub Packages REST API의 패키지 버전 응답에는 컨테이너 digest에 해당하는 `name`, `created_at`, `updated_at`, 태그 목록이 포함됩니다. digest와 버전 레코드를 연결하여 `created_at`을 사용하는 방안이 있습니다. 하지만 이것은 패키지 버전 생성 시각이며, 비공개에서 공개로 전환한 시각이나 태그가 해당 digest를 가리키기 시작한 시각까지 보장하지 않습니다. `updated_at`을 새 이미지 공개 시각으로 간주해서도 안 됩니다.

```text
GET /orgs/{org}/packages/container/{package_name}/versions
```

기존 `gh` 인증으로 4개 모두 조회를 시도했으나 HTTP 403과 `read:packages` scope 필요 메시지를 받았습니다. 이번 조사에서 인증 권한은 변경하지 않았습니다. 실제 응답 값, digest 연결, 페이지 순회 비용은 권한이 준비된 뒤 추가 검증해야 합니다.

클러스터의 해당 Deployment 4개에는 imagePullSecrets가 없었습니다. Immich machine learning의 `release-openvino`는 `skopeo inspect --no-creds --no-tags`로 익명 조회에 성공했습니다. 즉 공개 이미지 manifest 조회와 GitHub Packages 메타데이터 조회의 인증 요구는 다릅니다.

이 이미지에는 config의 `Created`와 인덱스의 `org.opencontainers.image.created`가 있지만, 이미지 생성 시각이므로 공개 시각으로 대체하지 않습니다. 익명 pull만으로 공개 시각까지 얻을 수 있다고 가정해서는 안 됩니다.

근거: [GitHub Packages REST API](https://docs.github.com/en/rest/packages/packages#list-package-versions-for-a-package-owned-by-an-organization), [OCI Image Config](https://specs.opencontainers.org/image-spec/config/).

## 개인 레지스트리: 현재 응답에서 공개 시각 확인 불가

`hub.sixtyfive.me`는 클러스터의 `registry:3.1.1` 서비스이며, 대상은 `transfer-shortener:latest`와 `watch-cluster:v0.50.46`입니다.

두 Deployment가 사용하는 기존 pull Secret의 인증을 각 레지스트리 요청에만 전달하여 manifest를 조회했습니다. 인증값은 문서나 응답 출력에 기록하지 않았습니다.

- 두 요청 모두 HTTP 200이고 `Docker-Content-Digest`를 반환했습니다.
- `transfer-shortener`는 단일 이미지 manifest, `watch-cluster`는 OCI index였습니다.
- 두 응답 모두 `Last-Modified`와 공개 시각 annotation이 없었습니다. HTTP `Date`는 요청 응답 시각이므로 사용하지 않습니다.
- OCI Distribution의 manifest·tag 조회 규격에는 공통으로 보장되는 공개 시각 필드가 없습니다. digest만 알면 시각도 계산할 수 있는 구조가 아닙니다.

공개 시각 기준을 유지하려면 향후 registry push 이벤트나 CI의 push 완료 기록을 저장하는 등 별도 출처가 필요합니다. 기존 이미지의 과거 push 시각을 그러한 기록 없이 복원할 수 있다고 가정하지 마십시오. 이 방식은 이번 조사에서 구현하거나 설정하지 않았습니다.

근거: [OCI Distribution Spec](https://specs.opencontainers.org/distribution-spec/), [규격 원문](https://github.com/opencontainers/distribution-spec/blob/main/spec.md).

## 구현 전 남은 결정과 제안

1. **Docker Hub의 마지막 push 시각을 실용적인 기준으로 허용할지 결정하십시오.** 엄밀한 최초 공개 시각과는 다르지만 현재 대상 15개에서 조회할 수 있었습니다. 허용한다면 선택한 digest와 같은 응답의 시각을 사용하고, digest 불일치나 null은 시각 확인 실패로 다루는 방향을 권합니다.
2. **GHCR 대상의 Docker Hub 전환을 우선 검토하십시오.** 추가 조사에서 4개 모두 공식 Docker Hub 배포를 확인했습니다. 전환하지 않고 GHCR 시각 API를 지원하려면 현재 `gh` OAuth 인증에서는 `read:packages`가 필요하며, 앱 인증 방식과 실제 시각 값을 추가 검증해야 합니다.
3. **개인 레지스트리 정책은 조사 후 확정되었습니다.** `hub.sixtyfive.me`는 유예 기간을 항상 `0`으로 취급하고 시각 조회와 유예 판단을 건너뜁니다. 다른 사설 레지스트리에 대한 일반 정책으로 확대하지 않습니다.
4. **시각 조회 실패와 설정 오류를 구분하십시오.** 잘못된 기간 설정은 이미 결정한 대로 경고 후 무시합니다. 유효한 유예 설정에서 시각 조회가 실패한 경우는 별도이며, 일시적인 API 실패는 보류 후 재시도를 권합니다. 아직 사용자 확정 정책은 아닙니다.

추가 구현 검토: 현재 `latest` 전략은 digest로 변경을 감지하지만 적용 이미지 문자열에서는 digest를 제거합니다. 확인 이후 태그가 변경되면 유예를 검증하지 않은 이미지가 pull될 수 있습니다. rollout 후 예상 digest 확인만으로는 사전 적용을 막을 수 없으므로, 유예를 검증한 digest로 적용하는 방법을 함께 설계해야 합니다. 기존 태그 추적과 업데이트 흐름은 유지해야 합니다.

이 조사로 애플리케이션 코드, 클러스터 설정, 이미지, 인증 권한은 변경하지 않았습니다.

## 추가 조사: GHCR 이미지의 공식 Docker Hub 대안

2026-09-16 공식 프로젝트 문서·배포 workflow와 Docker Hub API, GHCR manifest를 비교했습니다. 아래 네 태그 모두 Docker Hub에서 digest와 `tag_last_pushed`, amd64 이미지의 `last_pushed`를 조회했습니다.

| 서비스 | Docker Hub 대체 이미지 | GHCR과 비교 |
|---|---|---|
| Kavita | `jvmilazz0/kavita:0.9.1` | 최상위 digest 일치: `sha256:31181a32f0dda73cae68721867028a7253d57881b58bea5754cd9e578e75421a` |
| Reloader | `stakater/reloader:v1.4.2` | 공식 동일 버전이지만 최상위·amd64 manifest digest는 다릅니다. 아래 비교 결과를 참고하십시오. |
| Immich server | `altran1502/immich-server:v3.2.2` | 최상위 digest 일치: `sha256:79cc1623323d5894922686d8743b4780181428f98eecbfb58ce12c41ef02d1ea` |
| Immich machine learning | `altran1502/immich-machine-learning:release-openvino` | 최상위 digest 일치: `sha256:4013ec28ccf6344d7ae24554743a116d7f61124b98858f5646a401d5c5df12e2` |

Kavita 공식 문서는 Docker Hub와 GHCR이 같은 이미지라고 설명하며, release workflow도 두 저장소에 함께 게시합니다. LinuxServer 등 다른 패키징의 이미지로 바꿀 필요가 없습니다.

Reloader 공식 release workflow는 Docker Hub와 GHCR 각각에 build/push를 수행합니다. 현재 `v1.4.2`의 amd64 이미지를 비교한 결과 소스 revision(`d6f407d6f5ccb6a5efa44e978e9c06df41e21b24`), 생성 시각, 사용자, Entrypoint, Labels와 모든 rootfs diff ID가 일치했습니다. 하지만 manifest digest는 다르므로 GHCR digest를 Docker Hub 경로에 그대로 붙이지 마십시오. 실제 전환 시 Docker Hub에서 확인한 digest를 사용하고 rollout을 검증해야 합니다. 이번 조사에서 서비스 실행 검증은 하지 않았습니다.

Immich의 `altran1502` 경로는 공식 저장소의 `Mirror to Docker Hub` 작업이 게시하는 대상입니다. 해당 workflow는 정식 release 이벤트에서 server와 ML 변형(OpenVINO 포함)을 GHCR에서 Docker Hub로 복사합니다. 따라서 임의의 제3자 재빌드로 대체할 필요가 없습니다. 다만 정식 release 조건의 미러이므로 GHCR의 모든 개발·사전 릴리스 태그까지 같다고 가정하지 마십시오.

네 대상을 전환하면 현재 관리 대상 구성은 Docker Hub 19개와 유예 제외 개인 레지스트리 2개가 됩니다. 유예 기준 시각은 실제로 사용할 Docker Hub 쪽 push 시각으로 판단하는 방향이며, GHCR보다 미러 게시가 늦으면 그만큼 늦은 시각을 사용합니다. 레지스트리 전환은 제안 단계이며 클러스터는 변경하지 않았습니다.

근거:

- [Kavita 공식 Docker Hub 안내](https://wiki.kavitareader.com/installation/docker/dockerhub/), [GHCR과 동일 이미지 안내](https://wiki.kavitareader.com/installation/docker/github/), [공식 release workflow](https://github.com/Kareadita/Kavita/blob/develop/.github/workflows/release-workflow.yml)
- [Reloader 공식 release workflow](https://github.com/stakater/Reloader/blob/master/.github/workflows/release.yaml)
- [Immich 공식 Docker workflow](https://github.com/immich-app/immich/blob/main/.github/workflows/docker.yml)
