### Added — named areas and location aliases (data)

V189 adds the tables for named areas ("Hadrian's Wall") and location aliases ("the Heugh" for Hartlepool Headland): `area`, `area_alias`, `area_location` and `location_alias`, with the matching `AreaEntity`, `LocationAliasEntity` and repositories. Hadrian's Wall is seeded with its aliases and, by name, the Wall locations that exist; Hartlepool Headland gets its three Heugh aliases. Data only: Ask does not read them until the next phase (`docs/engineering/ask-named-areas-plan.md`, A1).
