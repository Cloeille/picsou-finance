/**
 * Stands in for every external service an instance could call. It answers 503 to everything, so
 * an endpoint that reaches out behaves as it does when the service is down, and nothing reaches
 * the internet. Hits are kept for the run log, not compared: background jobs make their timing
 * nondeterministic.
 */
export interface StubServer {
  url: string;
  port: number;
  hits: string[];
  stop(): void;
}

export function startStub(): StubServer {
  const hits: string[] = [];
  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    fetch(req) {
      const u = new URL(req.url);
      hits.push(`${req.method} ${req.headers.get("host") ?? ""}${u.pathname}`);
      return Response.json(
        { title: "Service Unavailable", status: 503, detail: "contract harness stub" },
        { status: 503 },
      );
    },
  });
  return { url: `http://127.0.0.1:${server.port}`, port: server.port!, hits, stop: () => server.stop(true) };
}
