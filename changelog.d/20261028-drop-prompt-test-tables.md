### Removed — prompt test tables

The empty `prompt_test_run` and `prompt_test_result` tables are dropped (migration V198). The Prompt Test harness was deleted in the previous release and its rows cleared; this finishes the clean-up one release later so a rollback to that release still validates against the schema.
