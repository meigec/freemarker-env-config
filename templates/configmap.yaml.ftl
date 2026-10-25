<#import "lib/macros.ftl" as m>
<@m.header file="configmap.yaml"/>
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${app.name}-config
  namespace: ${client.id}-${env.name}
  labels:
    app: ${app.name}
    client: ${client.id}
    environment: ${env.name}
data:
  LOG_LEVEL: "${app.logLevel!"INFO"}"
  REPLICAS: "${app.replicas?c}"
<#list services as svc>
  ${svc.name?upper_case}_URL: "${m.serviceUrl(svc)}"
</#list>
  DB_PASSWORD_REF: "${m.secretRef("db-password")}"
