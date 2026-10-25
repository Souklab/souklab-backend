# Configuration Package (`com.project.souklab.config`)

Centralizes framework configurations, custom Spring Beans, security filter setup, asynchronous execution, caching policies, and application property bindings.

---

## Key Responsibilities

- Configures Spring Security filter chains, stateless JWT authentication, hardened security headers (CSP, Permissions-Policy, Referrer-Policy, Frame-Options), and CORS policies.
- Enforces the Principle of Least Privilege on public read paths (`/feed`, `/catalog/**`, `/public/**`, `/subscriptions/plans`, `/artisans/*/reviews`), restricting unauthenticated access strictly to `HttpMethod.GET`.
- Sets up asynchronous task executors (`AsyncConfig`) and deterministic clocks (`ClockConfig`).
- Configures Caffeine in-memory caches for reference taxonomy data; security rate limits use the shared Bucket4j backend in production.
- Binds externalized configuration properties (`*Properties`) and framework-level proxy header strategy (`server.forward-headers-strategy=framework`).
- Configures WebSocket endpoints, STOMP message routing, and authentication handshakes (`WebSocketConfig`).
- Seeds canonical permissions and reference data; administrator bootstrap is explicitly property-gated (`DataSeeder`).
- Harmonizes outer HTTP status codes with inner envelope codes (`ApiResponseCodeAdvice`).
- Manages Hibernate Search 8.2.2.Final with Elasticsearch analysis and indexing lifecycle in subpackage [`search`](search/README.md).

---

## Classes Reference

| Class | Type | Responsibility |
| :--- | :--- | :--- |
| [`SecurityConfig`](SecurityConfig.java) | `@Configuration` | Configures `SecurityFilterChain`, CORS rules, hardened headers (CSP, Permissions-Policy), method-specific public endpoint permissions, and filter order. |
| [`AvatarProperties`](AvatarProperties.java) | `@ConfigurationProperties(prefix = "avatar")` | Configures avatar quotas, MIME policy, and upload rate limiting. |
| [`CacheConfig`](CacheConfig.java) | `@Configuration`, `@EnableCaching` | Configures Caffeine cache manager and names for Wilayas, categories, materials, epoques, and techniques. |
| [`AsyncConfig`](AsyncConfig.java) | `@Configuration`, `@EnableAsync` | Configures thread pools for non-blocking operations (audit logging, email dispatch). |
| [`ClockConfig`](ClockConfig.java) | `@Configuration` | Exposes a `java.time.Clock` bean for time-dependent operations (token expiration, cooldown tracking). |
| [`PasswordEncoderConfig`](PasswordEncoderConfig.java) | `@Configuration` | Exposes a `BCryptPasswordEncoder` bean for secure credential hashing. |
| [`ApiResponseCodeAdvice`](ApiResponseCodeAdvice.java) | `@ControllerAdvice` | Intercepts HTTP response bodies to synchronize outer HTTP status codes with inner `ApiResponse.code`. |
| [`DataSeeder`](DataSeeder.java) | `@Component`, `CommandLineRunner` | Seeds canonical permissions and reference data; creates an admin only when `APP_ADMIN_BOOTSTRAP_ENABLED=true`. |
| [`ConfigurationPolicyValidator`](ConfigurationPolicyValidator.java) | `@Component` | Rejects invalid limits, MIME policies, CORS credentials/origin combinations, and unsafe production storage settings. |
| [`WebSocketConfig`](WebSocketConfig.java) | `@Configuration`, `@EnableWebSocketMessageBroker` | Configures STOMP messaging, `/ws` endpoint, user destination prefixes, and external broker relays. |
| [`WebSocketAuthInterceptor`](WebSocketAuthInterceptor.java) | `ChannelInterceptor` | Authenticates STOMP `CONNECT` frames by validating Bearer JWT tokens in connect headers. |
| [`WebClientConfig`](WebClientConfig.java) | Class | Foundation configuration class for external HTTP client integrations. |
| [`OpenApiConfiguration`](OpenApiConfiguration.java) | `@Configuration` | Configures Swagger/OpenAPI documentation definitions, security schemes, and server URLs. |
| [`Phase9JacksonConfiguration`](Phase9JacksonConfiguration.java) | `@Configuration` | Custom Jackson serializers and deserializers for financial, audit, and pagination types. |
| [`AnalyticsRabbitConfiguration`](AnalyticsRabbitConfiguration.java) | `@Configuration` | Configures RabbitMQ exchanges, queues, and bindings for analytics outbox events. |
| [`MailerSendConfiguration`](MailerSendConfiguration.java) | `@Configuration` | Configures MailerSend API integration for transactional email delivery. |
| [`SubscriptionSchedulingConfiguration`](SubscriptionSchedulingConfiguration.java) | `@Configuration`, `@EnableScheduling` | Enables background scheduled tasks for subscription renewals, grace periods, and expired checkouts. |
| [`RateLimitRedisConfiguration`](RateLimitRedisConfiguration.java) | `@Configuration` | Configures Redis connection and proxy manager for Bucket4j rate-limiting. |

---

## Configuration Properties

| Class | Prefix | Description |
| :--- | :--- | :--- |
| [`SubscriptionProperties`](SubscriptionProperties.java) | `app.subscription` | Configuration for subscription grace periods, trial lengths, and billing behavior. |
| [`ChargilyProperties`](ChargilyProperties.java) | `chargily` | Chargily Pay V2 credentials, endpoints, webhook secrets, and callback URLs. |
| [`AnalyticsProperties`](AnalyticsProperties.java) | `app.analytics` | Root analytics configuration for rollups, outbox relays, and event processing. |
| [`AnalyticsJobProperties`](AnalyticsJobProperties.java) | `app.analytics.jobs` | Limits and thread pool settings for asynchronous analytics reporting jobs. |
| [`AnalyticsExportProperties`](AnalyticsExportProperties.java) | `app.analytics.export` | Configuration for analytics CSV/JSON file generation and S3 storage artifact paths. |
| [`AnalyticsRabbitProperties`](AnalyticsRabbitProperties.java) | `app.analytics.rabbitmq` | Exchange and routing key definitions for analytics event messaging. |
| [`AnalyticsRetentionProperties`](AnalyticsRetentionProperties.java) | `app.analytics.retention` | Retention windows and purge thresholds for historical raw activity events. |
| [`OpenApiProperties`](OpenApiProperties.java) | `app.openapi` | Metadata for OpenAPI API documentation generator (title, version, contacts). |
| [`RateLimitEndpointProperties`](RateLimitEndpointProperties.java) | `app.rate-limit` | Endpoint-specific rate limiting configurations and capacity thresholds. |
| [`HealthProperties`](HealthProperties.java) | `management.health` | Health indicator probe thresholds and timeout settings. |

---

## Health Indicators & Observability

| Class | Type | Responsibility |
| :--- | :--- | :--- |
| [`RelayHealthIndicator`](RelayHealthIndicator.java) | `HealthIndicator` | Actuator probe monitoring external STOMP message broker relay connectivity. |
| [`RedisHealthIndicator`](RedisHealthIndicator.java) | `HealthIndicator` | Actuator probe monitoring Redis server availability and ping responsiveness. |
| [`ElasticsearchHealthIndicator`](ElasticsearchHealthIndicator.java) | `HealthIndicator` | Actuator probe verifying Elasticsearch cluster health and search index status. |
| [`ChargilyHealthIndicator`](ChargilyHealthIndicator.java) | `HealthIndicator` | Actuator probe monitoring Chargily payment gateway connectivity and balance. |
| [`OperationalMetrics`](OperationalMetrics.java) | Component | Central registry tracking Micrometer custom operational metrics and request timers. |
| [`OperationalRequestMetricsFilter`](OperationalRequestMetricsFilter.java) | `OncePerRequestFilter` | Records HTTP request duration, status code counters, and endpoint latency metrics. |
| [`CorrelationIdFilter`](CorrelationIdFilter.java) | `OncePerRequestFilter` | Stamps unique correlation IDs (`X-Correlation-ID`) across incoming requests and MDC context. |
| [`RateLimitRule`](RateLimitRule.java) | Record | Encapsulates rate limit bucket limits, duration windows, and token refill rates. |
| [`SubCategorySeed`](SubCategorySeed.java), [`MaterialSeed`](MaterialSeed.java) | Records | Internal seed data definition structures for reference taxonomy bootstrapping in `DataSeeder`. |

---

## Subpackages

| Subpackage | Responsibility |
| :--- | :--- |
| [`search`](search/README.md) | Custom Elasticsearch analysis configurer and startup mass indexing runner for Hibernate Search. |
