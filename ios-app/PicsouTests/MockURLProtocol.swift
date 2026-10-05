import Foundation

/// Intercepts URLSession requests in tests. Set `MockURLProtocol.handler` to map a request to a
/// `(HTTPURLResponse, Data)` pair.
final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = MockURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    static func ok(_ request: URLRequest, json: String) -> (HTTPURLResponse, Data) {
        (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data(json.utf8))
    }

    static func status(_ request: URLRequest, _ code: Int, json: String = "") -> (HTTPURLResponse, Data) {
        (HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: nil)!, Data(json.utf8))
    }

    /// The backend security entry point's 401 for a missing, expired or revoked access token.
    static func tokenRejected(_ request: URLRequest) -> (HTTPURLResponse, Data) {
        status(request, 401, json:
            #"{"type":"urn:picsou:problem:authentication-required","status":401,"title":"Unauthorized","detail":"Authentication required"}"#)
    }
}
