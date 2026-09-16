{{/*
Naming rule the whole stand rests on: a service is reachable as <name>.<domain>,
because that is the name its Kerberos principal and its own configuration files
already carry. CoreDNS rewrites that name to the Service rendered here, so the
Service name and the values key must stay identical.
*/}}

{{- define "stand.labels" -}}
app.kubernetes.io/part-of: kudos-stand
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ .root.Chart.Name }}-{{ .root.Chart.Version }}
{{- end -}}

{{- define "stand.selectorLabels" -}}
app.kubernetes.io/part-of: kudos-stand
app.kubernetes.io/name: {{ .name }}
{{- end -}}

{{/*
Seeds the shared volume with the keytabs and krb5.conf that FreeIPA — still in
Docker Compose — issued. The volume cannot be the Secret itself: Ozone writes the
S3 secret it mints into the same directory at runtime, exactly as in the compose
stand, and a Secret mount is read-only. Copying is idempotent, so every pod may
run this without coordinating.

-L matters: a Secret mounts as a farm of symlinks into ..data, and copying those
verbatim leaves every name pointing at a path that does not exist here. The mode
matters too — a Secret arrives 0400 root, which root-run services read and
Trino, running as uid 1000, does not. 0644 is what these files have in the
compose stand.
*/}}
{{- define "stand.kerberosInit" -}}
- name: seed-kerberos
  image: busybox:1.36
  imagePullPolicy: {{ .root.Values.image.pullPolicy }}
  command:
    - sh
    - -c
    - >-
      cp -rL /kerberos-source/. {{ .root.Values.shared.mountPath }}/ &&
      chmod 0644 {{ .root.Values.shared.mountPath }}/* &&
      test -r {{ .root.Values.shared.mountPath }}/krb5.conf
  volumeMounts:
    - name: shared
      mountPath: {{ .root.Values.shared.mountPath }}
    - name: kerberos-source
      mountPath: /kerberos-source
      readOnly: true
{{- end -}}

{{/* Waits for another stand service to answer on a port, replacing compose's depends_on. */}}
{{- define "stand.waitFor" -}}
- name: wait-for-{{ .service }}
  image: busybox:1.36
  imagePullPolicy: {{ .root.Values.image.pullPolicy }}
  command:
    - sh
    - -c
    - until nc -z {{ .service }}.{{ .root.Values.domain }} {{ .port }}; do sleep 3; done
{{- end -}}

{{- define "stand.sharedVolumes" -}}
- name: shared
  persistentVolumeClaim:
    claimName: {{ .root.Values.shared.claimName }}
- name: kerberos-source
  secret:
    secretName: {{ .root.Values.existing.kerberosSecret }}
    defaultMode: 0400
{{- end -}}

{{- define "stand.sharedMounts" -}}
- name: shared
  mountPath: {{ .root.Values.shared.mountPath }}
{{- end -}}

{{/*
Gives a pod its own <name>.<domain> identity. Hadoop, Ozone, HBase and Kyuubi all
write their principals as `<service>/_HOST@REALM` and expand _HOST from the local
canonical hostname. In compose that works because each service sets
`hostname: <name>.test.local`; a pod would otherwise call itself `hdfs-0` and ask
the KDC for a principal that was never issued. Pointing that name at 127.0.0.1 through hostAliases does NOT work: the service
then binds to and advertises loopback, and no other pod can reach it. The
principals are given concrete names instead, by the config the bring-up script
derives from the same XML the compose stand mounts.
*/}}
{{- define "stand.hostIdentity" -}}
hostname: {{ .name }}
{{- end -}}
