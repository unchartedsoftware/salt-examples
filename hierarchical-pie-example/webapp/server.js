var express = require('express');
var sqlite3 = require('sqlite3');
var app = express();

var db = new sqlite3.Database('../output/fs_stats.sqlite', sqlite3.OPEN_READONLY);

app.use(express.static('app'));

app.get('/data*', function(req, res) {
  console.log(req.path);
  //TODO query sqlite database for all directories UNDER the supplied path
});

var server = app.listen(3000, function () {
  var port = server.address().port;
  console.log('Salt Hierarchical Pie example listening at http://localhost:%s', port);
});
