# Salt Torque Example

> Shows a single day of New York taxi cab pickups animated over the course of the day

This example illustrates how to use Salt to generate TileJSON output compatible with [CartoDB's Torque](https://github.com/CartoDB/Torque) library. Salt features used:

 - Loading and using CSV data in Spark
 - Custom, non-trivial bin aggregator
 - Compound tiling jobs (generates pickup and dropoff layers concurrently)
 - Custom output format serialization
 - Saving results to local filesystem on Spark Master

## Building the Example

To build the example you must first generate the TileJSON data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory.

To generate, run:
```
salt-examples/torque-example/ $ ./gradlew
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp/`:

```
npm install
npm start
```

The application will be available at http://localhost:3000/
