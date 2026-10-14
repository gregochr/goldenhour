# P1 export and analysis scripts

P1 measures wasted trips, the canvas rule and far cloud decks on the forecast history we already hold.
Plan: [docs/engineering/far-cloud-p1-plan.md](../../docs/engineering/far-cloud-p1-plan.md). The SQL here is read-only (one SELECT inside COPY each).

## Export (run once, on the production Docker host)

**Stored prompts are pruned after 30 days. Run the prompt export first.**

```
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_prompts.sql  > p1_prompts.csv
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_forecasts.sql > p1_forecasts.csv
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_summary.sql   > p1_summary.csv
```

From the Mac over ssh (the `<` file is read locally, the CSV lands locally):

```
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_prompts.sql  > p1_prompts.csv
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_forecasts.sql > p1_forecasts.csv
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_summary.sql   > p1_summary.csv
```

## Checking the files

- `head -1 p1_forecasts.csv` should show the column header (it starts `evaluation_id,location_id,target_date,...`); same for the other two.
- `wc -l` is a rough guide only: prompts contain newlines inside quoted fields, so their line count exceeds the row count.
- `p1_summary.csv` is small; read it whole. It lists the first SCORED, first `sky_rating` and first SONNET_55 run, counts by month, lead and event, and the ids of the Angel of the North, Low Hauxley and Copt Hill locations.
- A psql error stops the export (`ON_ERROR_STOP=1`); an empty CSV means it failed, so do not use it.

The CSVs hold place names, coordinates and model text but no user data. **Do not commit them**: `.gitignore` here ignores `*.csv`, `out/` and `cache/`.

## Next steps (scripts not yet written)

- `premise.py`: Part B, how often recent gos stood on premises that ERA5 contradicts, by model.
- `replay_set.py`: Part D, builds the replay prompt set from the prompt export and prints the cost estimate.
- `probe.py`: checks every API assumption in the plan and exits non-zero on any failure.
- `case.py`: Part A, what lay on the Angel's sun line, by model.
- `size.py`: Part C, whether far decks are common and forecastable, with the pilot.
