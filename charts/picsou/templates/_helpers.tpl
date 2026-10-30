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
{{- printf "%s:%s" .image.repository (toString $tag) }}
{{- end }}

{{- define "picsou.pullPolicy" -}}
{{- .image.pullPolicy | default .ctx.Values.global.imagePullPolicy }}
{{- end }}

{{/*
Secret holding sidecar-api-key, postgres-password and the optional app secrets.
*/}}
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
Restarts every pod when secret values set through the chart change, so the app
and the sidecars always share the same APP_SIDECAR_API_KEY. Hashes the values,
not the rendered Secret: generated values are random on first render.
*/}}
{{- define "picsou.secretChecksum" -}}
checksum/secret: {{ .Values.secrets | toYaml | sha256sum }}
{{- end }}
