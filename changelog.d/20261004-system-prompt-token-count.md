### Docs — the batch system prompts measured against the Haiku caching floor

A new on-demand `prompt-regression` test (`SystemPromptTokenCountTest`, free `count_tokens` calls)
measured the system prompts on `claude-haiku-4-5-20251001`: inland 4,726 tokens, coastal 4,779
(floor 4,096, margin about 15%), woodland 1,190, bluebell 968 (1,416 and 1,194 with the output
config attached). The estimates in `BatchRequestFactory`'s javadoc are replaced with these numbers.
At the measured 3.79 characters per token the 15,500-character offline guard is about 4,090 tokens,
just under the floor, so it is a proxy and not a guarantee. Bluebell is 56 tokens under Sonnet's
1,024 floor on system text alone, so its caching stays unproven.
