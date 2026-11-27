### Fixed — Ask names the favourite companion itself, looks up a named area, and frames a stay-home weekend first

- When `rank_spots` pairs the lead with a favourite of the reader's (or a tied or higher-rated place), the engine now appends one fixed sentence naming it with its stars if the model's summary left it out, such as "Mary's Lighthouse, your favourite, is 4★." It fires only when the first pick is the paired lead, and only within the summary's word and character caps.
- The typed Ask prompt tells the model to call `look_up_place` for a named area even when it also calls `rank_spots`, and to name the place by its listed name in the summary.
- Both Ask prompts say that when every window asked about is a stay-home one, the summary's first sentence says so and the forecast's pick follows as the first exception.
