# Review controller

Formation backed reviews are private formation data. The public artisan profile review routes stay available for forward compatibility with client reviews, but currently return no formation backed entries.

- `GET /api/v1/artisans/{artisanId}/reviews` returns an empty `PaginatedResponse` until client reviews are implemented.
- `GET /api/v1/artisan/reviews/{reviewId}` returns 404 for formation reviews.
- `GET /api/v1/artisan/formations/{formationId}/reviews` returns formation reviews only to artisan formation managers and admins; anonymous and client viewers receive 404.
- `GET /api/v1/artisan/formations/{formationId}/reviews/me` retrieves the authenticated artisan's own review for an enrollment, or returns `null` if not yet reviewed.
- Review submission requires an `ATTENDED` enrollment and a `COMPLETED` formation.

| Controller | Responsibility |
| --- | --- |
| [`ArtisanReviewController`](ArtisanReviewController.java) | Private formation review listing and authenticated artisan review lifecycle operations. |
