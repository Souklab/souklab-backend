# Directory DTO Package (`com.project.souklab.dto.directory`)

Data Transfer Objects and enumerations for public artisan directory queries, multi-facet filtering, and card presentations.

---

## Classes Reference

| Class / Enum | Type | Description |
| :--- | :---: | :--- |
| [`ArtisanDirectoryCardDTO`](ArtisanDirectoryCardDTO.java) | Outbound DTO | Public search result card representing an artisan with bio snippet, craft taxonomies, and location; rating fields are reserved for client reviews and currently zero. |
| [`DirectorySearchFilterDTO`](DirectorySearchFilterDTO.java) | Inbound DTO | Validated search criteria encapsulation including keyword, Wilaya, category, materials, epoques, and sort options. |
| [`DirectorySortOrder`](DirectorySortOrder.java) | Grouped enums | Supported search ranking orders (`DirectorySortOrder.Relevance.DEFAULT`, `DirectorySortOrder.Rating.DESC`, `DirectorySortOrder.Reviews.DESC`, `DirectorySortOrder.Views.DESC`, `DirectorySortOrder.Newest.FIRST`). |
| [`DirectorySortOrderConverter`](DirectorySortOrderConverter.java) | Converter | Converts query parameter strings into `DirectorySortOrder` instances. |
| [`DirectorySortOrderDeserializer`](DirectorySortOrderDeserializer.java) | Deserializer | Deserializes JSON representations into `DirectorySortOrder` instances. |
| [`DirectorySortOrderValueDeserializer`](DirectorySortOrderValueDeserializer.java) | Deserializer | Deserializes wire values into `DirectorySortOrder` instances. |
