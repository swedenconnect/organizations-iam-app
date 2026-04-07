CREATE DATABASE keycloak;

CREATE USER keycloak WITH PASSWORD 'secret';

GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak;

\c keycloak;
CREATE SCHEMA keycloak;

GRANT ALL PRIVILEGES ON SCHEMA keycloak TO keycloak;
