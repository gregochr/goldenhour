### Removed — Swagger UI dropped from the build

The backend now depends on `springdoc-openapi-starter-webmvc-api` instead of the `-ui` starter, so the
swagger-ui webjar (and the DOMPurify copy inside it flagged as CVE-2026-116803 and CVE-2026-116804) is no
longer in the jar. `/v3/api-docs` is unchanged for the ZAP pen-test stack; production already had the UI
switched off. The `/swagger-ui/**` permit rule and the dead `springdoc.swagger-ui` setting are removed.
