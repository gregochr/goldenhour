### Fixed — account emails carry a plain-text version for spam filters

Every account email (welcome, password reset by an administrator, reset-by-link, verification, the two
verification reminders and the account-removed notice) was HTML-only, which Outlook/Hotmail and Gmail spam
filters mark down. Each is now a `multipart/alternative` message: a hand-written plain-text part first, then
the same HTML as before. The text twins live in `templates/text/*.txt`, rendered by a TEXT-mode Thymeleaf
resolver for `text/*` template names (`EmailTextTemplateConfig`); the HTML templates and their copy are
unchanged.
