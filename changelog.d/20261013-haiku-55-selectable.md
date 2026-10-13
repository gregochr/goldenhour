### Added — Claude Haiku 5.5 as a selectable evaluation model

`EvaluationModel.HAIKU_55` (`claude-haiku-5-5`) can now be chosen on the Models screen for any run
type, and on the Prompt Test, Sky-Rating Eval and best-bet advisor replay harnesses. It is not the
default or active model anywhere, and it is left out of the scheduled sky-rating eval, the Model
Test and Briefing Model Test comparison lists and Ask PhotoCast's model setting.

Haiku 5.5 thinks by default, but unlike Sonnet 5.5 it accepts `thinking: disabled`, so every
request path (synchronous, batch, cache primer, aurora, glosses, best-bet advisor) sends thinking
disabled with effort low and keeps the Haiku answer budgets. Costs are configured as `cost.haiku55-*`
($0.10 in / $0.50 out per million tokens for prompts up to 100K tokens; every prompt here is far
smaller), roughly a tenth of Haiku 4.5 even though its tokeniser counts about 30% more tokens. No
migration: the model columns hold the 8-character name `HAIKU_55`.

Not yet run against the prompt-regression suite or the sky-rating eval; do that before switching any
run type to it.

The existing Haiku model is now labelled "Haiku 4.5" everywhere a model name is shown, so the two Haikus are told apart.
