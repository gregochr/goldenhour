### Added — Ask: follow-up questions — the answers stack and each question carries the session (T4: client)

Ask's conversation is now a thread on the client (`docs/engineering/ask-thread-plan.md` §2.5; the server half is T1–T3). Every
answered exchange of the session, typed or Ready, is kept (the last eight), and the next typed question sends them with it as
`thread` — a first question's request is exactly what it was. The answers stack: each earlier exchange is drawn collapsed above
the live answer (the reader's question and the answer's summary, plain text, nothing to press), the chips say "follow-up · N so
far", and "Clear answer" reads "Clear" once there is a thread to lose. A failed, refused or can't-answer step never ends the
thread. When the server answers a follow-up against a newer forecast it drops the thread (`threadReset`); the client empties its
own first and shows one line above the fresh answer: "The forecast has updated since your last question — this is a fresh
answer." The map's picks and camera, and the phone peek's minimised line, follow the live answer alone, and the conversation
keeps one live region (earlier exchanges are static text and are not announced again). A follow-up scrolls the conversation to its
new question, leaving the field focused. Still behind `photocast.ask.enabled`.
