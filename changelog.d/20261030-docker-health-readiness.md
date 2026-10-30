### Fixed — an Open-Meteo quota no longer marks the production container unhealthy

The Docker healthcheck, the compose healthcheck and the rollback script's health wait curled
`/actuator/health`, whose aggregate includes the external-service indicators (Open-Meteo, Claude,
WorldTides) and the circuit breakers. When Open-Meteo hit its daily quota (429) the aggregate went DOWN
(503), the container reported `unhealthy` and the post-deploy check failed although the app was serving
normally. They now curl a new anonymous `container` health group (`/actuator/health/container`) that
contains only `db` and `ping`, and shows status only. `/actuator/health` and the in-app status pill are
unchanged.
