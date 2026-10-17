// Exercise the actual CloudFront function without network or a cloud deployment.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'spa.js'), 'utf8'), context);
for (const [input, expected] of [
  ['/', '/index.html'],
  ['/billing/invoices', '/index.html'],
  ['/finance/', '/index.html'],
  ['/main.123abc.js', '/main.123abc.js'],
  ['/assets/logo.svg', '/assets/logo.svg'],
]) {
  const request = { uri: input, querystring: { intentId: { value: 'opaque-id' } } };
  const result = context.handler({ request });
  assert.equal(result.uri, expected);
  assert.equal(result.querystring.intentId.value, 'opaque-id');
}
console.log('CloudFront SPA function: 5 route/query preservation checks passed.');
