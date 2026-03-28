{{/*
Expand the name of the chart.
*/}}
{{- define "payment-api.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
Truncates to 63 characters because Kubernetes names are limited to 63 characters.
*/}}
{{- define "payment-api.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart label value — name + version, safe for label use.
*/}}
{{- define "payment-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels — applied to every resource so selectors + tooling work uniformly.
*/}}
{{- define "payment-api.labels" -}}
helm.sh/chart: {{ include "payment-api.chart" . }}
{{ include "payment-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — used by Service and Deployment selectors.
Must be stable; changing these after first deploy breaks rolling updates.
*/}}
{{- define "payment-api.selectorLabels" -}}
app.kubernetes.io/name: {{ include "payment-api.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount name.
*/}}
{{- define "payment-api.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "payment-api.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image reference — repository:tag.
image.tag MUST be set explicitly at deploy time (--set image.tag=sha-<shortsha>).
Falling back to Chart.AppVersion is unsafe because the SNAPSHOT tag will not
exist in GHCR and the pods will enter ImagePullBackOff.
*/}}
{{- define "payment-api.image" -}}
{{- if not .Values.image.tag }}
  {{- fail "image.tag must be set explicitly — e.g. --set image.tag=sha-abc1234. Do not leave it empty." }}
{{- end }}
{{- printf "%s:%s" .Values.image.repository .Values.image.tag }}
{{- end }}
