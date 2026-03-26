-- CS6650 Assignment 3: Full initialization script
-- Run from database directory:
--   psql -h localhost -U chat -d chatdb -f 04_init_all.sql
-- Or run files separately: 01_schema.sql, 02_indexes.sql, 03_views.sql

\ir 01_schema.sql
\ir 02_indexes.sql
\ir 03_views.sql
