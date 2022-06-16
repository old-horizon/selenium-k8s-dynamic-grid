{{- define "selenium-dynamic-grid.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "selenium-dynamic-grid.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "selenium-dynamic-grid.labels" -}}
helm.sh/chart: {{ include "selenium-dynamic-grid.chart" . }}
{{ include "selenium-dynamic-grid.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "selenium-dynamic-grid.selectorLabels" -}}
app.kubernetes.io/name: {{ include "selenium-dynamic-grid.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "selenium-dynamic-grid.serviceAccountName" -}}
{{- default (include "selenium-dynamic-grid.name" .) .Values.serviceAccount.name }}
{{- end }}
