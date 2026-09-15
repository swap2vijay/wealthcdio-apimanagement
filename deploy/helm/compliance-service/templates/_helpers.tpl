{{/*
=============================================================================
Naming and labelling helpers.

A copy of the ledger service's, not a shared library chart. Two files of near
identical boilerplate is the price of the two charts having no build-time
coupling, and it is the right trade here: a shared library chart would mean a
change intended for one service could break the other's rendering, which is
exactly the coupling that separate charts exist to avoid.
=============================================================================
*/}}

{{- define "compliance-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The name the objects actually get.

values.yaml pins fullnameOverride to "compliance-service" so that the ledger
chart's config.complianceBaseUrl (http://compliance-service:8081) resolves. That
pin is a real coupling between the two charts, and putting it in values rather
than hiding it in here is deliberate - it is the sort of thing that should be
visible when someone renames a release and wonders why transfers stopped.
*/}}
{{- define "compliance-service.fullname" -}}
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

{{- define "compliance-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Metadata labels. Kept separate from the selector for the same reason as the other
chart: a Deployment's selector is immutable after creation, so anything that
changes per release - the version label in particular - must stay out of it or the
next upgrade fails with "field is immutable" and needs the Deployment deleted.
*/}}
{{- define "compliance-service.labels" -}}
helm.sh/chart: {{ include "compliance-service.chart" . }}
{{ include "compliance-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: wealthcdio-apimanagement
{{- end -}}

{{- define "compliance-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "compliance-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "compliance-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "compliance-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
