# Vault Agent config (static). The dynamic bits — Vault address, PKI mount/role,
# certificate common name and TTL — come from environment variables the chart
# sets on the container, so this file needs no Helm templating. AppRole auth is
# at auth/approle; edit here if your AppRole mount differs.
#
# The three templates issue with identical arguments, so consul-template
# deduplicates them into a single pki/issue call — certificate, key and issuing
# CA always match. Used both as an init container (-exit-after-auth issues once
# before the app starts) and as a sidecar (renews in place).

pid_file = "/home/vault/.pid"

auto_auth {
  method "approle" {
    mount_path = "auth/approle"
    config = {
      role_id_file_path                   = "/vault/approle/role_id"
      secret_id_file_path                 = "/vault/approle/secret_id"
      remove_secret_id_file_after_reading = false
    }
  }
  sink "file" {
    config = { path = "/home/vault/.vault-token" }
  }
}

template {
  contents    = "{{ with secret (printf \"%s/issue/%s\" (env \"VAULT_PKI_PATH\") (env \"VAULT_PKI_ROLE\")) (printf \"common_name=%s\" (env \"VAULT_CN\")) (printf \"ttl=%s\" (env \"VAULT_TTL\")) }}{{ .Data.certificate }}\n{{ .Data.issuing_ca }}{{ end }}"
  destination = "/vault/secrets/tls.crt"
}

template {
  contents    = "{{ with secret (printf \"%s/issue/%s\" (env \"VAULT_PKI_PATH\") (env \"VAULT_PKI_ROLE\")) (printf \"common_name=%s\" (env \"VAULT_CN\")) (printf \"ttl=%s\" (env \"VAULT_TTL\")) }}{{ .Data.private_key }}{{ end }}"
  destination = "/vault/secrets/tls.key"
}

template {
  contents    = "{{ with secret (printf \"%s/issue/%s\" (env \"VAULT_PKI_PATH\") (env \"VAULT_PKI_ROLE\")) (printf \"common_name=%s\" (env \"VAULT_CN\")) (printf \"ttl=%s\" (env \"VAULT_TTL\")) }}{{ .Data.issuing_ca }}{{ end }}"
  destination = "/vault/secrets/ca.crt"
}
