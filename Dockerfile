#
# Salt Example Container
# Apache Spark 1.6.0
#
# Build using:
# $ docker build -t uncharted/salt-examples .
#

FROM uncharted/sparklet:1.6.0
MAINTAINER Sean McIntyre <smcintyre@uncharted.software>

# Mount point for source directories
WORKDIR /opt/salt

# for dev environment
ENV GRADLE_OPTS -Dorg.gradle.native=false

CMD ["./gradlew"]

# Add sample data
ADD http://assets.oculusinfo.com/salt/sample-data/taxi_one_day.csv /opt/data/taxi_one_day.csv
ADD http://assets.oculusinfo.com/salt/sample-data/ubuntu-filelist.csv /opt/data/ubuntu-filelist.csv
