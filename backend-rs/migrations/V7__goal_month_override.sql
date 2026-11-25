CREATE TABLE goal_month_override (
    id         BIGSERIAL      PRIMARY KEY,
    goal_id    BIGINT         NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
    year_month VARCHAR(7)     NOT NULL,
    amount     NUMERIC(20, 2) NOT NULL,
    UNIQUE (goal_id, year_month)
);
