// Minimal fake Firefly III for the on-device auto-create flow test. Logs every request.
const http = require('http');
let nextId = 9001;
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    console.log(req.method + ' ' + req.url.split('?')[0] + (req.method === 'POST' ? ' ' + body.slice(0, 200) : ''));
    res.setHeader('Content-Type', 'application/json');
    if (req.method === 'POST' && req.url.startsWith('/api/v1/transactions')) {
      res.end(JSON.stringify({ data: { id: String(nextId++) } }));
    } else if (req.url.startsWith('/api/v1/transactions/')) {
      res.statusCode = 404; res.end('{}');
    } else {
      res.end(JSON.stringify({ data: [], meta: { pagination: { total_pages: 1 } } }));
    }
  });
}).listen(18080, () => console.log('listening'));
