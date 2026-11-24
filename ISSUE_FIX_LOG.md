# Issue Fix Log

This file records fixes merged into the fix/allissues integration branch. Each
issue uses its own issue/<issue-name> branch and its PR preserves that branch
after merge. Entries link the issue, implementation decisions, documentation,
verification, and PR.

## Backend

### souklab/souklab-backend#115 — Reject unauthenticated STOMP connect frames

- **Branch:** issue/websocket-reject-unauthenticated-stomp-frame-connects
- **Decision:** Treat STOMP and CONNECT as the same connection message type and
  reject subsequent inbound STOMP frames without a principal.
- **Changes:** Added STOMP alias authentication and unauthenticated-frame tests;
  updated the config package README.
- **Verification:** JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./mvnw -Dtest=WebSocketAuthInterceptorTest test (7 tests passed).
- **PR:** Pending.
