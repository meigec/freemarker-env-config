<#import "lib/macros.ftl" as m>
<@m.header file="application.properties"/>
app.name=${app.name}
app.client=${client.displayName}
app.environment=${env.name}
app.log.level=${app.logLevel!"INFO"}
app.replicas=${app.replicas?c}
<#if env.tier == "production">
app.debug.endpoints=false
<#else>
app.debug.endpoints=true
</#if>

<#list services as svc>
service.${svc.name}.url=${m.serviceUrl(svc)}
</#list>

<#list app.featureFlags as flag, enabled>
feature.${flag}=${enabled?c}
</#list>

db.url=jdbc:oracle:thin:@${db.host}:${db.port?c}/${db.schema}
db.pool.size=${db.poolSize?c}
db.password=${m.secretRef("db-password")}
