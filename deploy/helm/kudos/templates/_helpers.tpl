{{/* Chart name, overridable. */}}
{{- define "kudos.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app name. */}}
{{- define "kudos.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "kudos.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "kudos.labels" -}}
helm.sh/chart: {{ include "kudos.chart" . }}
{{ include "kudos.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "kudos.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kudos.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "kudos.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "kudos.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "kudos.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{/* Environment shared by the Vault Agent init container and sidecar. */}}
{{- define "kudos.vaultAgentEnv" -}}
- name: VAULT_ADDR
  value: {{ .Values.vault.address | quote }}
- name: VAULT_PKI_PATH
  value: {{ .Values.vault.pkiPath | quote }}
- name: VAULT_PKI_ROLE
  value: {{ .Values.vault.pkiRole | quote }}
- name: VAULT_CN
  value: {{ .Values.vault.commonName | quote }}
- name: VAULT_TTL
  value: {{ .Values.vault.ttl | quote }}
{{- if .Values.vault.caConfigMap }}
- name: VAULT_CACERT
  value: /vault/tls/ca.crt
{{- end }}
{{- end -}}

{{/* Volume mounts shared by the Vault Agent init container and sidecar. */}}
{{- define "kudos.vaultAgentMounts" -}}
- name: vault-config
  mountPath: /vault/config
  readOnly: true
- name: vault-approle
  mountPath: /vault/approle
  readOnly: true
- name: vault-secrets
  mountPath: /vault/secrets
- name: vault-home
  mountPath: /home/vault
{{- if .Values.vault.caConfigMap }}
- name: vault-ca
  mountPath: /vault/tls
  readOnly: true
{{- end }}
{{- end -}}
