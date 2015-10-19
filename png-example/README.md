# Salt PNG Example

> Shows a single day of New York taxi cab pickups

This example illustrates how to use Salt to generate PNG image output. Salt features used:

 - Loading and using CSV data in Spark
 - Count bin aggregator
 - Min-max tile aggregator
 - PNG image output format
 - Saving results to local filesystem on Spark Master

## Building the Example
To build the example you must first generate the PNG images data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory. If you plan on using the included Docker container to run the example, ensure that it's built before continuing (see [root README](../README.md)).

Until Salt exists in the central Maven repo, instructions are a little more involved:

#### While container cannot build examples or to run locally

Build the example JAR
```
salt-examples/png-example/generation $ ./gradlew assemble
```

Submit the built JAR to Spark
```
salt-examples/png-example/ $ docker run -it -v `pwd`/output:/opt/output -v `pwd`/generation:/opt/salt uncharted/salt-examples bash

container $ spark-submit --class software.uncharted.salt.examples.png.Main /opt/salt/build/libs/salt-png-example-0.1.0.jar /opt/data/taxi_one_day.csv /opt/output
```

Results are written to /opt/output in the container.


#### When container is able to build example JAR

Build the JAR and generate tiles in one command
```
salt-examples/png-example/ $ docker run --rm -v `pwd`/output:/opt/output -v `pwd`/generation:/opt/salt uncharted/salt-examples
```

To run the container interactively, run:
```
salt-examples/png-example/ $ docker run -it -v `pwd`/output:/opt/output -v `pwd`/generation:/opt/salt uncharted/salt-examples bash
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp\`:

```
npm install
npm run start
```

The application will be available at http://localhost:3000/
