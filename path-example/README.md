# Salt Path Drawing Example

> Shows a single day of New York taxi cab activity, with lines connecting pickup and dropoff points for each trip

This example is similar to the bin-example, but tiles paths between pickups and dropoffs instead of pickup points.

## Building the Example

To build the example you must first generate the .bin tile data (written to the `output/` directory) and then run the web app to view the results.

### Tile Generation

Tile generation is done using the code in the `generation/` directory.

To generate, run:
```
salt-examples/path-example/ $ ./gradlew
```

Make sure you have a fair bit of available RAM on your docker host for this example (4+GB), or it will run out of memory.

Note, you can now remove the resulting docker container by running:
```
salt-examples/path-example/ $ ./gradlew cleanGenEnv
```

### Viewing Results

Results are viewed through a simple web app contained in `webapp/`. After generating tiles, run from `webapp/`:

```
npm install
npm start
```

The application will be available at http://localhost:3000/
