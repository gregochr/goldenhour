# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Registration.zip` / `design_handoff_postcode_and_auth/`,
2026-10-16. **Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/login-registration-plan.md` §1 (corrections), §4 (deliberate disagreements) and §5
(decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Eighteen tasks in four groups: A postcode capture (registration `HOME` step, the one-time sign-in prompt, "Use my location"), B mobile-browser fixes, C auth fixes (consent default, registration form, forgot password, username-or-email, visual drift), D native-app prep (account deletion, `apple-app-site-association`, clean email links, privacy answers). It opens with five "check these first" questions; the plan's §1 answers every one. |
| `Postcode Capture.html` | **The design**, as eight static frames. Frames 1–4 are the new screens (registration `HOME` empty / looked up / invalid, and the sign-in prompt); frames 5–8 pair the current Register and Sign in screens with the suggested ones. **Design reference, not production code** — the README says so itself; rebuild in the existing React components with the existing tokens. Its inputs copy the CURRENT auth input style; the README's own "one known deviation" says to build the unified `.field` style of task 13 instead. |

**Nothing from the bundle is omitted.** It carries no screenshots and no shared JS.

⚠️ Two of the HTML's notes propose options the README later settles: frame 4's note says the dismissal
could live "in a new user setting or in localStorage" — the README (task 2) and the plan (§5 #3) choose
the server-side column; frame 3's note suggests a Pro-specific wording for the Pro line — the README
keeps one line for every role and so does the plan.
