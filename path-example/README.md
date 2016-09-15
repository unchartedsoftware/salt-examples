# Salt Path Drawing Example

> Shows a single day of New York taxi cab activity, with lines connecting pickup and dropoff points for each trip

This example is similar to the bin-example, but tiles paths between pickups and dropoffs instead of pickup points.

## Building the Example

To build the example you must first generate the .bin tile data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

First, we need to generate the tiles with the following commands:
```
cd generation/
./gradlew
./gradlew clean cleanGenEnv # clean up build environment
cd -
```

Make sure you have a fair bit of available RAM on your docker host for this example (4+GB), or it will run out of memory.

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run:

```
cd webapp
npm install
npm start
cd -
```

The application will be available at http://localhost:3000/
