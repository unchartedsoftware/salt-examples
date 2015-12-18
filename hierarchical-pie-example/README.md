# Salt Hierarchical Pie Example

> Shows aggregated filesystem statistics for a default Ubuntu 15.04 filesystem

This example illustrates how to use Salt to generate a visualization which is not based on map tiles, but instead involves a projection to a hierarchical pie chart space. Salt features used:

 - Loading and using CSV data in Spark
 - Count bin aggregator
 - Custom Projection
 - Saving results to a SQLite database on the local filesystem on Spark Master

The input dataset is a dump (with metadata) of an unused Ubuntu 15.04 filesystem:

```bash
$ find . -printf \"%p\",\"%m\",\"%M\",\"%s\",\"%u\",\"%U\"\\n
```

## Building the Example
To build the example you must first generate the SQLite data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory. If you plan on using the included Docker container to run the example, ensure that it's built before continuing (see [root README](../README.md)).

Build the JAR and generate tiles in one command
```
salt-examples/hierarchical-pie-example/ $ docker run --rm -v /$(pwd)/output:/opt/output -v /$(pwd)/generation:/opt/salt uncharted/salt-examples
```

To run the container interactively, run:
```
salt-examples/hierarchical-pie-example/ $ docker run -it -v /$(pwd)/output:/opt/output -v /$(pwd)/generation:/opt/salt uncharted/salt-examples bash
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp/`:

```
npm install
npm start
```

The application will be available at http://localhost:3000/
