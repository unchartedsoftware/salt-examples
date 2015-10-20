var express = require('express');
var compression = require('compression');
var app = express();

app.use(express.static('app'));
app.use('/tiles', express.static('../output'));
app.use('/meta', express.static('../output'));

var server = app.listen(3000, function () {
  var port = server.address().port;
  console.log('Salt Bin example listening at http://localhost:%s', port);
});