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

# Vault Agent for the test stand: authenticates to Vault with the AppRole the
# bootstrap created, issues the application's TLS certificate from the PKI
# engine, and writes it as PEM into the shared /vault/secrets volume the app
# reads. The same delivery contract (a Vault Agent sidecar writing
# /vault/secrets/tls.{crt,key}) is used by the Helm deployment.

pid_file = "/tmp/vault-agent.pid"

vault {
  address = "http://vault:8200"
}

auto_auth {
  method "approle" {
    config = {
      role_id_file_path                   = "/vault/approle/role_id"
      secret_id_file_path                 = "/vault/approle/secret_id"
      remove_secret_id_file_after_reading = false
    }
  }
  sink "file" {
    config = { path = "/tmp/vault-token" }
  }
}

# The three templates issue with byte-identical arguments, so consul-template
# deduplicates them into a SINGLE pki/issue call per render — the certificate,
# its key and the issuing CA therefore always match. Agent re-renders (re-issues)
# automatically as the lease nears expiry, rotating the material in place.
template {
  contents    = "{{ with secret \"pki/issue/kudos\" \"common_name=app.test.local\" \"alt_names=app.test.local\" \"ttl=24h\" }}{{ .Data.certificate }}\n{{ .Data.issuing_ca }}{{ end }}"
  destination = "/vault/secrets/tls.crt"
}

template {
  contents    = "{{ with secret \"pki/issue/kudos\" \"common_name=app.test.local\" \"alt_names=app.test.local\" \"ttl=24h\" }}{{ .Data.private_key }}{{ end }}"
  destination = "/vault/secrets/tls.key"
}

template {
  contents    = "{{ with secret \"pki/issue/kudos\" \"common_name=app.test.local\" \"alt_names=app.test.local\" \"ttl=24h\" }}{{ .Data.issuing_ca }}{{ end }}"
  destination = "/vault/secrets/ca.crt"
}

# One PEM keeps Trino's certificate and private key from the same Vault issue.
# The trino-tls service converts it to PKCS#12 for Trino and the JDBC truststore.
template {
  contents    = "{{ with secret \"pki/issue/kudos\" \"common_name=trino.test.local\" \"alt_names=trino.test.local\" \"ttl=24h\" }}{{ .Data.private_key }}\n{{ .Data.certificate }}\n{{ .Data.issuing_ca }}{{ end }}"
  destination = "/vault/secrets/trino.pem"
}
