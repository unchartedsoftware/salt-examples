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

Tile generation is done using the code in the `generation/` directory.

To generate, run:
```
salt-examples/heirarchical-pie-example/ $ ./gradlew
```

Note, you can now remove the resulting docker container by running:
```
salt-examples/heirarchical-pie-example/ $ ./gradlew cleanGenEnv
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp/`:

**Note:** Because this webapp relies on [sqlite3 npm package](https://www.npmjs.com/package/sqlite3), it will only work with node versions **5.x and lower** .
```
npm install
npm start
```

The application will be available at http://localhost:3000/
