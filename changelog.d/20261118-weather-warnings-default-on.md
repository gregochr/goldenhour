### Changed — Met Office weather warnings are on by default, dormant until an API key is set

`photocast.warnings.enabled` now defaults to true and stays as the kill switch (`PHOTOCAST_WARNINGS_ENABLED=false`). A new effective-on check (`WarningsProperties.isActive()`, switch on and an API key set) gates every user-visible surface, the poll job and its scheduler row, so a build without a key never calls the Met Office and looks exactly as it did with the switch off. The Operations warnings panel now states "Switched off", "On but no API key (dormant)" or "On and polling".
