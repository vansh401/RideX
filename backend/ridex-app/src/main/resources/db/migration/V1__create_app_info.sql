CREATE TABLE app_info (
    info_key   VARCHAR(100) PRIMARY KEY,
    info_value VARCHAR(255) NOT NULL
);

INSERT INTO app_info (info_key, info_value) VALUES ('app_name', 'RideX');