# Salt Torque Example

> Shows a single day of New York taxi cab pickups animated over the course of the day

This example illustrates how to use Salt to generate TileJSON output compatible with [CartoDB's Torque](https://github.com/CartoDB/Torque) library. Salt features used:

 - Loading and using CSV data in Spark
 - Non-trivial bin aggregator
 - Custom output format serialization
 - Saving results to local filesystem on Spark Master

## Building the Example
To build the example you must first generate the TileJSON data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory. If you plan on using the included Docker container to run the example, ensure that it's built before continuing (see [root README](../README.md)).

Until Salt exists in the central Maven repo, instructions are a little more involved:

#### While container cannot build examples or to run locally

Build the example JAR
```
salt-examples/torque-example/generation $ ./gradlew assemble
```

Submit the built JAR to Spark
```
salt-examples/torque-example/ $ docker run -it -v `pwd`/output:/opt/output -v `pwd`/generation:/opt/salt docker.uncharted.software/salt-examples bash

container $ spark-submit --class com.unchartedsoftware.salt.examples.torque.Main /opt/salt/build/libs/mosaic-torque-example-0.1.0.jar
```

Results are written to /opt/output in the container.


#### When container is able to build example JAR

Build the JAR and generate tiles in one command
```
salt-examples/torque-example/ $ docker run --rm -v `pwd`/output:/opt/output docker.uncharted.software/salt-examples
```

To run the container interactively, run:
```
salt-examples/torque-example/ $ docker run -it -v `pwd`/output:/opt/output -v `pwd`/generation:/opt/salt docker.uncharted.software/salt-examples bash
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp\`:

```
npm install
npm run start
```

The application will be available at http://localhost:3000/
