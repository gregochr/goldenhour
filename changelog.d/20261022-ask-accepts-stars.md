### Fixed — Ask PhotoCast accepts star ratings in a question

A typed question such as "Why is Alnmouth 4* on Saturday morning?" was refused with "Questions can only use
letters, numbers and ordinary punctuation", although every rating PhotoCast shows is in stars. The question
allow-list in `AskQuestionSanitiser` now also takes `*`, `★` and `☆`; the cache-key form treats them as a word
break, so "4*", "4★" and "4" share a key. The ⭐ emoji stays refused, like every other emoji.
