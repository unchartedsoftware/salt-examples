#
# Salt Example Container
# Apache Spark 1.5.1
#
# Build using:
# $ docker build -t docker.uncharted.software/salt-examples .
#

FROM uncharted/sparklet:1.5.1
MAINTAINER Sean McIntyre <smcintyre@uncharted.software>

# Add sample data
ADD http://assets.oculusinfo.com/salt/sample-data/taxi_one_day.csv /opt/data/taxi_one_day.csv

# Mount point for source directories
WORKDIR /opt/salt

# for dev environment
ENV GRADLE_OPTS -Dorg.gradle.daemon=true

CMD ["./gradlew"]
