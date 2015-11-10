FROM zalando/openjdk:8u66-b17-1-2

MAINTAINER Zalando SE

COPY target/twintip-crawler.jar /

CMD java $(java-dynamic-memory-opts) $(newrelic-agent) $(appdynamics-agent) -jar /twintip-crawler.jar

ADD target/scm-source.json /scm-source.json
