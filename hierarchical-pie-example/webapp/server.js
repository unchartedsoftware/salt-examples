var express = require('express');
var sqlite3 = require('sqlite3');
var app = express();

var db = new sqlite3.Database('../output/fs_stats.sqlite', sqlite3.OPEN_READONLY);

function getChildren(dirname, done) {
  if (dirname === '/') {
    dirname = '';
  }
  db.all("SELECT * FROM fs_stats WHERE path = '."+dirname+"'", function(err, rows) {
    done(err, rows);
  });
}

app.use(express.static('app'));

app.get('/data*', function(req, res) {
  var root = req.path.substring(5);
  console.log('GET ' + root);
  getChildren(root, function(err, results) {
    if (err) {
      res.status(500).send(err);
    } else {
      res.send(results);
    }
  });
});

var server = app.listen(3000, function () {
  var port = server.address().port;
  console.log('Salt Hierarchical Pie example listening at http://localhost:%s', port);
});
