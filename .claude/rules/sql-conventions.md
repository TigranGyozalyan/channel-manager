---
paths:
  - "src/**/*.sql"
---

# SQL error conventions

Return structured errors from SQL using the `[ELSCI-4xx]` prefix — `PgErrorParser` + `RestExceptionHandler.handleDbErrors` maps them automatically to the right HTTP status. Never let raw PostgreSQL errors surface for validation failures.

```sql
RAISE EXCEPTION '[ELSCI-400] Your validation message here';
RAISE EXCEPTION '[ELSCI-403] User % is not a member of board %', user_id, board_id;
RAISE EXCEPTION '[ELSCI-404] Board % doesn''t exist', board_id;
```

| Prefix | HTTP | Meaning |
|---|---|---|
| `[ELSCI-400]` | 400 | validation / semantic error |
| `[ELSCI-401]` | 401 | anonymous caller on a non-public board — authentication required |
| `[ELSCI-402]` | 403 | quota exceeded (raised by `elsci_check_*_limits`) |
| `[ELSCI-403]` | 403 | no access / not a member |
| `[ELSCI-404]` | 404 | board doesn't exist |
