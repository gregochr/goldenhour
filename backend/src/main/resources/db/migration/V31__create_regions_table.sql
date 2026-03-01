CREATE TABLE regions (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

INSERT INTO regions (name) VALUES ('Tyne and Wear');
INSERT INTO regions (name) VALUES ('The North Yorkshire Coast');
INSERT INTO regions (name) VALUES ('The Lake District');
INSERT INTO regions (name) VALUES ('The Yorkshire Dales');
INSERT INTO regions (name) VALUES ('Northumberland');
