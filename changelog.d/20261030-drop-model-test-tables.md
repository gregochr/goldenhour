### Removed — model test tables

Migration V200 drops the four tables behind the retired Location Model Test and Briefing Model Test screens (`model_test_run`, `model_test_result`, `briefing_model_test_run`, `briefing_model_test_result`), now that the release without their entities has shipped. Dropping `model_test_result` also removes the old foreign key that stopped regions and locations being deleted. Local H2 keeps the tables (`ddl-auto: update` never drops).
