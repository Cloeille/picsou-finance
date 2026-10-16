import Foundation
import Network

/// A one-route HTTP/1.1 server on 127.0.0.1. Unlike `MockURLProtocol`, requests go through the real
/// URL loading system, so cookie storage and the `Cookie` header behave as they do against a server.
/// Every response sets `responseHeaders`; `cookieHeaders` records each request's `Cookie` header.
final class LocalHTTPServer: @unchecked Sendable {
    let responseHeaders: [String: String]
    private let listener: NWListener
    private let queue = DispatchQueue(label: "LocalHTTPServer")
    private let lock = NSLock()
    private var cookies: [String?] = []

    var cookieHeaders: [String?] {
        lock.lock(); defer { lock.unlock() }
        return cookies
    }

    init(responseHeaders: [String: String]) throws {
        self.responseHeaders = responseHeaders
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    /// Starts listening and returns the base URL once the port is bound.
    func start() async throws -> URL {
        listener.newConnectionHandler = { [weak self] connection in self?.serve(connection) }
        let port: UInt16 = try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    continuation.resume(returning: listener.port!.rawValue)
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        return URL(string: "http://127.0.0.1:\(port)")!
    }

    func stop() {
        listener.cancel()
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        receiveHead(connection, buffer: Data())
    }

    private func receiveHead(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            guard let head = String(data: buffer, encoding: .utf8), head.contains("\r\n\r\n") else {
                if isComplete || error != nil { connection.cancel() } else { self.receiveHead(connection, buffer: buffer) }
                return
            }
            self.record(head)
            self.respond(connection)
        }
    }

    private func record(_ head: String) {
        let cookie = head.components(separatedBy: "\r\n")
            .first { $0.lowercased().hasPrefix("cookie:") }
            .map { String($0.dropFirst("cookie:".count)).trimmingCharacters(in: .whitespaces) }
        lock.lock(); defer { lock.unlock() }
        cookies.append(cookie)
    }

    private func respond(_ connection: NWConnection) {
        let body = "{}"
        var lines = ["HTTP/1.1 200 OK", "Content-Type: application/json",
                     "Content-Length: \(body.utf8.count)", "Connection: close"]
        lines += responseHeaders.map { "\($0.key): \($0.value)" }
        let response = lines.joined(separator: "\r\n") + "\r\n\r\n" + body
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
    }
}
