{{/*
=============================================================================
Naming and labelling helpers.

Every name in the chart comes from here rather than being spelled out in each
template. That is not tidiness for its own sake: the Service's selector and the
Deployment's pod labels have to agree exactly, and the surest way to make two
things agree is to generate both from one function.
=============================================================================
*/}}

{{/*
The chart name, overridable. Truncated to 63 characters because that is the
limit for a Kubernetes label value, and these names end up in labels.
*/}}
{{- define "ledger-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The fully qualified name used for the actual objects.

Normally "<release>-<chart>", but when the release is already named after the
chart the prefix is dropped: a release called "ledger-service" should produce
"ledger-service", not "ledger-service-ledger-service".

This matters beyond aesthetics. The compliance base URL in values.yaml is
http://compliance-service:8081, which only resolves if that chart's objects are
actually named "compliance-service". Predictable names are what make in-cluster
DNS between the two services predictable.
*/}}
{{- define "ledger-service.fullname" -}}
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

{{- define "ledger-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Full label set, for metadata only.

Deliberately NOT used as the Service selector. app.kubernetes.io/version changes
on every release, and a Deployment's selector is immutable once created - if the
selector contained the version, the next upgrade would fail with "field is
immutable" and the only way out would be deleting the Deployment. Hence the split
into labels (rich, changeable) and selectorLabels (minimal, stable).
*/}}
{{- define "ledger-service.labels" -}}
helm.sh/chart: {{ include "ledger-service.chart" . }}
{{ include "ledger-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: wealthcdio-apimanagement
{{- end -}}

{{/*
The stable subset. These two labels, and only these two, identify the pods.
*/}}
{{- define "ledger-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ledger-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "ledger-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "ledger-service.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
