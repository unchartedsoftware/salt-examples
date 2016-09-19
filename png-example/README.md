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

First, we need to generate the tiles with the following commands:
```
cd generation/
./gradlew
./gradlew clean cleanGenEnv # clean up build environment
cd -
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run:

```
cd webapp
npm install
npm start
cd -
```

The application will be available at http://localhost:3000/

### Cleanup

You can remove the generated data by running:
```
cd generation/
./gradlew cleanOutput
cd -
```
