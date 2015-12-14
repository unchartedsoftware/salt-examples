var express = require('express');
var sqlite3 = require('sqlite3');
var async = require('async');
var app = express();

var db = new sqlite3.Database('../output/fs_stats.sqlite', sqlite3.OPEN_READONLY);

function getData(dirname, done) {
  if (dirname === '/') {
    dirname = '';
  }
  getChildren(dirname, 2, function(err, children) {
    var breakPt = dirname.lastIndexOf('/');
    var results = {
      path: '.'+dirname.slice(0,breakPt),
      filename: dirname.slice(breakPt+1),
      children: children
    };
    done(err, results);
  });
}

function getChildren(dirname, depth, done) {
  getRows(dirname, function(err, rows) {
    if (depth > 1) {
      // Plow down another level, for each child add children
      async.map(rows, function(item, cb) {
        var path = item.path.slice(1) + '/' + item.filename;
        getChildren(path, depth-1, function(err, rows) {
          item.children = rows;
          cb(err, item);
        });
      }, function(err, results) {
        // Done level, return results
        done(err, results);
      });
    } else {
      // No children needed, return results directly
      done(err, rows);
    }
  });
}

function getRows(dirname, done) {
  db.all("SELECT * FROM fs_stats WHERE path = '."+dirname+"'", function(err, rows) {
    done(err, rows);
  });
}

app.use(express.static('app'));

app.get('/data*', function(req, res) {
  var root = req.path.substring(5);
  console.log('GET ' + root);
  getData(root, function(err, results) {
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
