### Fixed — expired refresh tokens are purged again

The scheduled `refresh_token_cleanup` job failed on every fire with "No EntityManager with actual transaction available" because its derived delete ran without a transaction, so expired refresh tokens were never removed. The delete is now one bulk `DELETE` that carries its own transaction.
