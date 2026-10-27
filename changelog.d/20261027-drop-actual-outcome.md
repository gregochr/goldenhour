### Removed — the legacy actual_outcome table is dropped

Migration V195 drops the `actual_outcome` table that the old free-text outcome API wrote to; its code was removed earlier. Its one real row was moved into `outcome_rating` on production first.
