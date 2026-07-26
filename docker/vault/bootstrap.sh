#!/bin/sh
# Copyright 2026 Aleksey Martynov and contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# One-shot configuration of the dev-mode Vault for the test stand:
#  - a PKI secrets engine with a self-signed root CA and an issuing role,
#  - an AppRole the Vault Agent uses to authenticate,
#  - the role_id / secret_id written to a shared volume for the agent.
#
# Idempotent enough to re-run on every `docker compose up`; the dev Vault is
# in-memory, so a fresh container is reconfigured from scratch.
set -eu

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-root}"

echo "[vault-bootstrap] waiting for Vault at $VAULT_ADDR"
until vault status >/dev/null 2>&1; do sleep 1; done

DOMAIN="${PKI_DOMAIN:-test.local}"

echo "[vault-bootstrap] enabling PKI engine + root CA"
vault secrets enable pki 2>/dev/null || true
vault secrets tune -max-lease-ttl=87600h pki
if ! vault read pki/cert/ca >/dev/null 2>&1; then
  vault write -field=certificate pki/root/generate/internal \
    common_name="${DOMAIN} Root CA" ttl=87600h > /vault/approle/ca.crt
fi
vault write pki/config/urls \
  issuing_certificates="${VAULT_ADDR}/v1/pki/ca" \
  crl_distribution_points="${VAULT_ADDR}/v1/pki/crl"

echo "[vault-bootstrap] creating issuing role 'kudos' for *.$DOMAIN"
vault write pki/roles/kudos \
  allowed_domains="${DOMAIN}" \
  allow_subdomains=true \
  allow_bare_domains=true \
  max_ttl=72h

echo "[vault-bootstrap] enabling AppRole + policy"
vault auth enable approle 2>/dev/null || true
vault policy write kudos - <<'EOF'
path "pki/issue/kudos" {
  capabilities = ["create", "update"]
}
EOF
vault write auth/approle/role/kudos \
  token_policies=kudos token_ttl=1h token_max_ttl=4h \
  secret_id_ttl=0 secret_id_num_uses=0

vault read -field=role_id auth/approle/role/kudos/role-id > /vault/approle/role_id
vault write -f -field=secret_id auth/approle/role/kudos/secret-id > /vault/approle/secret_id
chmod 0640 /vault/approle/role_id /vault/approle/secret_id

echo "[vault-bootstrap] done; role_id/secret_id written to /vault/approle"
