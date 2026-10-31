{{/*
Chart name, truncated to fit a DNS label.
*/}}
{{- define "picsou.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified release name. Kept to 40 characters so that
"<fullname>-caisse-epargne-auth" still fits in a 63-character DNS label: the
backend only accepts plain HTTP to a sidecar on a single-label host name.
*/}}
{{- define "picsou.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 40 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 40 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 40 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "picsou.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "picsou.labels" -}}
helm.sh/chart: {{ include "picsou.chart" . }}
app.kubernetes.io/name: {{ include "picsou.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: picsou
{{- end }}

{{/*
Selector labels for one component. Call with (dict "ctx" $ "component" "app").
*/}}
{{- define "picsou.selectorLabels" -}}
app.kubernetes.io/name: {{ include "picsou.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "picsou.componentLabels" -}}
{{ include "picsou.labels" .ctx }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
Service name of a sidecar. Call with (dict "ctx" $ "name" "bourso-auth").
*/}}
{{- define "picsou.sidecarName" -}}
{{- printf "%s-%s" (include "picsou.fullname" .ctx) .name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Image reference. Call with (dict "ctx" $ "image" .Values.app.image).
*/}}
{{- define "picsou.image" -}}
{{- $tag := .image.tag | default .ctx.Values.global.imageTag | default .ctx.Chart.AppVersion }}
{{- $repository := .image.repository | default .ctx.Values.global.imageRepository }}
{{- printf "%s:%s" $repository (toString $tag) }}
{{- end }}

{{- define "picsou.pullPolicy" -}}
{{- .image.pullPolicy | default .ctx.Values.global.imagePullPolicy }}
{{- end }}

{{- define "picsou.secret.inlineValue" -}}
{{- /* default treats 0 and false as empty; only nil must bypass stringification. */ -}}
{{- if not (kindIs "invalid" .) -}}
{{- toString . -}}
{{- end -}}
{{- end }}

{{/* Secret holding sidecar-api-key, postgres-password and the optional app secrets. */}}
{{- define "picsou.secretName" -}}
{{- .Values.secrets.existingSecret | default (include "picsou.fullname" .) }}
{{- end }}

{{- define "picsou.postgresql.fullname" -}}
{{- printf "%s-postgresql" (include "picsou.fullname" .) }}
{{- end }}

{{- define "picsou.database.host" -}}
{{- if .Values.postgresql.enabled }}
{{- include "picsou.postgresql.fullname" . }}
{{- else }}
{{- required "externalDatabase.host is required when postgresql.enabled=false" .Values.externalDatabase.host }}
{{- end }}
{{- end }}

{{- define "picsou.database.port" -}}
{{- if .Values.postgresql.enabled }}5432{{ else }}{{ .Values.externalDatabase.port }}{{ end }}
{{- end }}

{{- define "picsou.database.name" -}}
{{- if .Values.postgresql.enabled }}{{ .Values.postgresql.database }}{{ else }}{{ .Values.externalDatabase.database }}{{ end }}
{{- end }}

{{- define "picsou.database.username" -}}
{{- if .Values.postgresql.enabled }}{{ .Values.postgresql.username }}{{ else }}{{ .Values.externalDatabase.username }}{{ end }}
{{- end }}

{{- define "picsou.database.passwordSecret" -}}
{{- if and (not .Values.postgresql.enabled) (not .Values.externalDatabase.existingSecret) (not .Values.secrets.existingSecret) (not .Values.secrets.postgresPassword) }}
{{- fail "external database requires externalDatabase.existingSecret, secrets.existingSecret or secrets.postgresPassword when postgresql.enabled=false" }}
{{- end }}
{{- if and (not .Values.postgresql.enabled) .Values.externalDatabase.existingSecret }}
{{- .Values.externalDatabase.existingSecret }}
{{- else }}
{{- include "picsou.secretName" . }}
{{- end }}
{{- end }}

{{- define "picsou.database.passwordKey" -}}
{{- if and (not .Values.postgresql.enabled) .Values.externalDatabase.existingSecret }}
{{- .Values.externalDatabase.passwordKey }}
{{- else }}postgres-password{{ end }}
{{- end }}

{{- define "picsou.database.jdbcUrl" -}}
{{- $params := ternary "sslmode=prefer" .Values.externalDatabase.jdbcParams .Values.postgresql.enabled }}
{{- printf "jdbc:postgresql://%s:%s/%s" (include "picsou.database.host" .) (include "picsou.database.port" .) (include "picsou.database.name" .) }}
{{- with $params }}?{{ . }}{{ end }}
{{- end }}

{{/*
Restarts app and sidecar pods when inline secret values change. Hashes values,
not the rendered Secret: generated values are random on first render. External
Secret rotations require an operator rollout of the app and all sidecars.
*/}}
{{- define "picsou.secretChecksum" -}}
checksum/secret: {{ .Values.secrets | toYaml | sha256sum }}
{{- end }}

{{/* Shared datasource defaults. Explicit user entries replace defaults by name. */}}
{{- define "picsou.database.env" -}}
{{- $overrides := deepCopy (.Values.app.env | default dict) }}
{{- range .Values.app.extraEnv }}
{{- $_ := set $overrides .name true }}
{{- end }}
{{- if not (hasKey $overrides "SPRING_DATASOURCE_URL") }}
- name: SPRING_DATASOURCE_URL
  value: {{ include "picsou.database.jdbcUrl" . | quote }}
{{- end }}
{{- if not (hasKey $overrides "SPRING_DATASOURCE_USERNAME") }}
- name: SPRING_DATASOURCE_USERNAME
  value: {{ include "picsou.database.username" . | quote }}
{{- end }}
{{- if not (hasKey $overrides "POSTGRES_PASSWORD") }}
- name: POSTGRES_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "picsou.database.passwordSecret" . }}
      key: {{ include "picsou.database.passwordKey" . }}
{{- end }}
{{- end }}

{{/* extraEnv wins over env; reject duplicates within extraEnv for server-side apply. */}}
{{- define "picsou.app.userEnv" -}}
{{- $extraNames := dict }}
{{- range .Values.app.extraEnv }}
{{- if hasKey $extraNames .name }}
{{- fail (printf "app.extraEnv contains duplicate name %s" .name) }}
{{- end }}
{{- $_ := set $extraNames .name true }}
{{- end }}
{{- range $k, $v := .Values.app.env }}
{{- if not (hasKey $extraNames $k) }}
- name: {{ $k }}
  value: {{ toString $v | quote }}
{{- end }}
{{- end }}
{{- with .Values.app.extraEnv }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end }}

{{/* Every generated app variable can be replaced by an explicit user entry. */}}
{{- define "picsou.app.env" -}}
{{- $userEnv := include "picsou.app.userEnv" . | fromYamlArray }}
{{- $overrides := dict }}
{{- range $userEnv }}
{{- $_ := set $overrides .name true }}
{{- end }}
{{- range (include "picsou.app.defaultEnv" . | fromYamlArray) }}
{{- if not (hasKey $overrides .name) }}
{{ toYaml (list .) }}
{{- end }}
{{- end }}
{{- with $userEnv }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- define "picsou.app.defaultEnv" -}}
{{- $secret := include "picsou.secretName" . }}
{{- include "picsou.database.env" . | nindent 0 }}
- name: APP_SIDECAR_API_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: sidecar-api-key
# With persistent /data, absent keys are generated by the entrypoint.
# Without it, require both keys even for an existing Secret.
- name: JWT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: jwt-secret
      optional: {{ .Values.app.persistence.enabled }}
- name: CRYPTO_ENCRYPTION_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secret }}
      key: crypto-encryption-key
      optional: {{ .Values.app.persistence.enabled }}
{{- range $name, $sc := .Values.sidecars }}
{{- if $sc.enabled }}
- name: {{ $sc.urlEnv }}
  value: {{ printf "http://%s:%v" (include "picsou.sidecarName" (dict "ctx" $ "name" $name)) $sc.port | quote }}
{{- end }}
{{- end }}
{{- with .Values.app.enableBanking.existingSecret }}
- name: ENABLEBANKING_PRIVATE_KEY_PATH
  value: /run/secrets/enablebanking/{{ $.Values.app.enableBanking.key }}
{{- end }}
{{- end }}
