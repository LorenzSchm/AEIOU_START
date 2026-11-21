from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.client import HTTPConnection

PATH = "/api/v1/mailbox/webhook"
class WebhookProxy(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != PATH:
            self.send_error(404)
            return
        try:
            size = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.send_error(411)
            return
        if not 0 < size <= 256 * 1024:
            self.send_error(413)
            return
        self.connection.settimeout(4)
        backend = HTTPConnection("127.0.0.1", 8000, timeout=4)
        try:
            body = self.rfile.read(size)
            headers = {key: self.headers[key] for key in (
                "Content-Type", "X-Webhook-Timestamp", "X-Webhook-Signature"
            ) if key in self.headers}
            backend.request("POST", PATH, body, headers)
            status = backend.getresponse().status
        except (OSError, TimeoutError):
            status = 503
        finally:
            backend.close()
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.send_error(404)

ThreadingHTTPServer(("127.0.0.1", 8765), WebhookProxy).serve_forever()
