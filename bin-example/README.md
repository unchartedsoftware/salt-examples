# Salt Bin Example

> Shows a single day of New York taxi cab pickups

This example illustrates how to use Salt to generate bin file output. Salt features used:

 - Loading and using CSV data in Spark
 - Count bin aggregator
 - Min-max tile aggregator
 - Bin file output format
 - Saving results to local filesystem on Spark Master

## Building the Example

To build the example you must first generate the .bin tile data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory.

To generate, run:
```
salt-examples/bin-example/ $ ./gradlew
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp/`:

```
npm install
npm start
```

The application will be available at http://localhost:3000/
