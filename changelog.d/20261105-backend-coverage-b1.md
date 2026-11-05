### Testing — Cover the evaluation services' branches (backend B1)

The `service.evaluation` and `service.evaluation.visitor` classes now sit at 95% lines and 90% branches or better, with behaviour tests for the
batch result handlers, the best-bet ranker, validator, parser and enricher, the superseding-disposition rules, custom-id parsing and the
call gate on the Ask client. No production code changed.
