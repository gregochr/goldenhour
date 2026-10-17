### Fixed — an admin could not delete a user who had drive times; the "account removed" email went out before the delete committed

`user_drive_time.user_id` (V67) has no `ON DELETE` rule, so on Postgres `DELETE /api/users/{id}` of any user who
had calculated drive times violated the foreign key and failed (H2, which the tests run on, has no such key, so it
never showed). Both deletion paths — the admin one and the new self-service one — now purge the account's drive
times in the deleting transaction, after the user row is locked and before it is deleted, through one
`purgeUserRows` step. The admin delete also locks the row first and reads the name and stored address from the
locked row, releases the account's email claim, and no longer sends the confirmation email from inside the
transaction: `UserService.deleteUser` returns the name and address and the controller sends the email after the
transaction has committed, so a delete that fails at commit no longer mails "your account was removed".
