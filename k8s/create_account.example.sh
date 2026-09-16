#!/bin/sh
# Copy this example to k8s/create_account.sh and edit the copy.
# Run from the repository root: sh k8s/create_account.sh
# Keep credentials out of this tracked example; the local copy is gitignored.
set +x
set -eu

ACCOUNT_ID=''
ACCOUNT_PASSWORD=''
NAMESPACE='watch-cluster'
SECRET_NAME='watch-cluster-basic-auth'

case "$ACCOUNT_ID" in
    ''|*[!a-zA-Z0-9._-]*)
        printf '%s\n' 'ACCOUNT_ID에 영문, 숫자, 점, 밑줄, 하이픈으로 된 ID를 입력하십시오.' >&2
        exit 1
        ;;
esac
if [ "${#ACCOUNT_ID}" -gt 253 ]; then
    printf '%s\n' 'ACCOUNT_ID는 253자 이하여야 합니다.' >&2
    exit 1
fi
case "$ACCOUNT_PASSWORD" in
    ''|*'
'*|*"$(printf '\r')"*)
        printf '%s\n' 'ACCOUNT_PASSWORD에 줄바꿈 없는 비밀번호를 입력하십시오.' >&2
        exit 1
        ;;
esac

for dependency in kubectl openssl jq mktemp; do
    if ! command -v "$dependency" >/dev/null 2>&1; then
        printf '필요한 명령을 찾을 수 없습니다: %s\n' "$dependency" >&2
        exit 1
    fi
done

umask 077
account_tmp_dir=$(mktemp -d)
trap 'rm -rf "$account_tmp_dir"' 0
trap 'exit 1' 1 2 15

# Pass the password through a private file, never a process argument.
printf '%s\n' "$ACCOUNT_PASSWORD" > "$account_tmp_dir/password"
unset ACCOUNT_PASSWORD

# Authentication/network errors stop execution; only NotFound is ignored.
kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" \
    --ignore-not-found -o json > "$account_tmp_dir/existing.json"
existing_hash=$(jq -r --arg id "$ACCOUNT_ID" \
    '.data[$id] // "" | @base64d' "$account_tmp_dir/existing.json")

# Reuse the existing SHA-512 crypt salt so identical credentials are a no-op.
# Other hash formats are replaced with SHA-512 crypt on the next update.
existing_salt=$(jq -nr --arg hash "$existing_hash" \
    '$hash | capture("^\\$6\\$(?<salt>[./0-9A-Za-z]{1,16})\\$").salt // empty')
if [ -n "$existing_salt" ]; then
    openssl passwd -6 -salt "$existing_salt" \
        -in "$account_tmp_dir/password" > "$account_tmp_dir/hash"
else
    openssl passwd -6 -in "$account_tmp_dir/password" > "$account_tmp_dir/hash"
fi
rm -f "$account_tmp_dir/password"

if [ "$existing_hash" = "$(cat "$account_tmp_dir/hash")" ]; then
    printf '계정 %s: 기존 비밀번호와 같으므로 변경하지 않았습니다.\n' "$ACCOUNT_ID"
    exit 0
fi

jq -n --arg id "$ACCOUNT_ID" --rawfile hash "$account_tmp_dir/hash" \
    '{data: {($id): ($hash | rtrimstr("\n") | @base64)}}' \
    > "$account_tmp_dir/patch.json"

if [ -s "$account_tmp_dir/existing.json" ]; then
    # Merge only this account, preserving other users and Secret metadata.
    kubectl patch secret "$SECRET_NAME" -n "$NAMESPACE" --type=merge \
        --patch-file "$account_tmp_dir/patch.json"
else
    jq --arg name "$SECRET_NAME" --arg namespace "$NAMESPACE" \
        '. + {apiVersion: "v1", kind: "Secret", type: "Opaque",
              metadata: {name: $name, namespace: $namespace}}' \
        "$account_tmp_dir/patch.json" > "$account_tmp_dir/secret.json"
    kubectl create -f "$account_tmp_dir/secret.json"
fi

printf '계정 %s을(를) Secret %s/%s에 저장했습니다.\n' \
    "$ACCOUNT_ID" "$NAMESPACE" "$SECRET_NAME"
