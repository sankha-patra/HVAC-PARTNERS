// Only attached to the S3 cache behavior. Never rewrite API failures to HTML.
function handler(event) {
    var request = event.request;
    var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);
    if (request.uri.endsWith('/') || lastSegment.indexOf('.') === -1) {
        request.uri = '/index.html';
    }
    return request;
}
