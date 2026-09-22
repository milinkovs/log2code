# Thin layer on top of a locally built PetClinic image (customers, visits, vets), see ADR-004.
#
# chaos-monkey-spring-boot 4.0.0 declares spring-boot-starter-restclient as a "provided" dependency and refers to
# org.springframework.boot.restclient.RestTemplateCustomizer. These three services do not use RestTemplate, so
# spring-boot-restclient (and its Spring Boot module spring-boot-http-client) is not in their jars and every start
# with the chaos-monkey profile fails with NoClassDefFoundError.
# The jars are added to the exploded Spring Boot layout (BOOT-INF/lib + classpath.idx). No PetClinic file is modified.
#
# Built by infra/scripts/build-petclinic.sh; the build context is a folder "jars/" that holds only those jars.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

# Space-separated file names of the jars in the context (same as in jars/).
ARG EXTRA_JARS
COPY jars/ /application/BOOT-INF/lib/
RUN for j in ${EXTRA_JARS}; do echo "- \"BOOT-INF/lib/$j\"" >> /application/BOOT-INF/classpath.idx; done
