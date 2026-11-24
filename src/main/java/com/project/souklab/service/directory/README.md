# Directory Search Service Package (`com.project.souklab.service.directory`)

Search orchestration service powering the public artisan discovery engine through Hibernate Search 8.2.2.Final with an Elasticsearch 8.x backend.

---

## Architectural Principles

- **Elasticsearch Full-Text Search**: Builds dynamic predicate trees and scoring algorithms with edge-n-gram analyzers, prefix matching, and phonetic/accent-insensitive normalizers.
- **Multi-Facet Filtering**: Supports faceted filtering across Wilayas, craft categories, subcategories, material families, materials, epoques, techniques, and teacher status. Rating fields remain zero and positive minimum-rating filters return no results until client reviews are implemented; rating/review sort keys currently use creation time to avoid ordering by formation reviews.
- **Resilient Fallback**: Designed with graceful degradation to relational JPA criteria specifications if the Elasticsearch search cluster is disabled or temporarily unreachable.

---

## Classes Reference

| Class / Interface | Type | Responsibility |
| :--- | :---: | :--- |
| [`DirectorySearchService`](DirectorySearchService.java) | Interface | Service contract defining full-text search, filtering, and pagination over the artisan directory. |
| [`DirectorySearchServiceImpl`](DirectorySearchServiceImpl.java) | Class | Hibernate Search + Elasticsearch query implementation with predicate composition and fallback logic. |
