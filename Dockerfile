FROM maven:3.6.3-jdk-8

ENV SRC_PATH=/src
ENV BUILD_OUTPUT_PATH=/build_output

RUN mkdir -p ${BUILD_OUTPUT_PATH}

WORKDIR ${SRC_PATH}

RUN cat <<'EOF' > /run.sh
#!/bin/bash
set -e
echo "=== Starting build ==="
cd ${SRC_PATH}

# Build all modules needed for the 'all' module (netty uber-jar)
# The 'all' module uses maven-bundle-plugin with packaging=bundle and unpack-dependencies
# to create the all-in-one JAR
if [ "$SEAL_SKIP_TESTS" = "1" ]; then
  mvn clean install -B -e -DskipTests \
    -Dmaven.javadoc.skip=true \
    -Dcheckstyle.skip=true \
    -Denforcer.skip=true \
    -Danimal.sniffer.skip=true \
    -Dmaven.source.skip=true \
    -Dgpg.skip=true \
    -pl common,buffer,codec,codec-http,codec-socks,transport,handler,metrics-yammer,example,all -am
else
  mvn clean install -B -e \
    -Dmaven.javadoc.skip=true \
    -Dcheckstyle.skip=true \
    -Denforcer.skip=true \
    -Danimal.sniffer.skip=true \
    -Dmaven.source.skip=true \
    -Dgpg.skip=true \
    -pl common,buffer,codec,codec-http,codec-socks,transport,handler,metrics-yammer,example,all -am
fi

echo "=== Copying JAR files ==="
cp all/target/netty-4.0.0.Alpha8.jar ${BUILD_OUTPUT_PATH}/
echo "=== Build complete ==="
ls -lh ${BUILD_OUTPUT_PATH}/
EOF

RUN chmod +x /run.sh

ENTRYPOINT ["/run.sh"]
