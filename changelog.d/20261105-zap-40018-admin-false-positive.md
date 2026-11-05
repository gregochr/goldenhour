### Changed — ZAP gate ignores a SQL-injection false positive on admin paths

The weekly ZAP gate now carries a narrow `ignore` exception for rule 40018 (SQL injection) on
backend URLs under `/api/admin/`. The scan runs as a non-admin, so the security filter answers every
request there with the same 403 before any parameter is read, which ZAP's expression check misreads
as a hit. The exception is scoped by URI: a 40018 alert on any other path, or an alert that mixes
admin and non-admin instances, still fails the gate.
