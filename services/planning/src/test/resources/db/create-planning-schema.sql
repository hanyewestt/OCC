CREATE ROLE occ_planning LOGIN PASSWORD 'synthetic-planning';
CREATE SCHEMA planning AUTHORIZATION occ_planning;
GRANT USAGE, CREATE ON SCHEMA planning TO occ_planning;
