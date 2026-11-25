# Issue fix log

Fixes are developed on issue branches and proposed as individual pull requests into `fix/allissues`. Issue branches are retained after merge.

| Issue | Change | Verification | PR |
| --- | --- | --- | --- |
| #115 | Reject unauthenticated STOMP 1.2 `STOMP` and `CONNECT` frames, including subsequent inbound frames without a principal. | `WebSocketAuthInterceptorTest` passed locally on JDK 21; full PR CI passed. | [#170](https://github.com/Souklab/souklab-backend/pull/170), merged |
| #81 | Scope formation reviews to protected formation route, hide them from public profile routes, remove reviewer email fallback, and stop formation review rating aggregation. | Focused review and directory suites passed on JDK 21; full CI rerun pending after correcting a stale rating assertion. | [#171](https://github.com/Souklab/souklab-backend/pull/171), open |
