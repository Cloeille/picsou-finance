-- V2: Goals and goal-account join table

CREATE TABLE goal (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(200)   NOT NULL,
    target_amount  NUMERIC(20, 2) NOT NULL,
    deadline       DATE           NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_goal_deadline CHECK (deadline > CURRENT_DATE)
);

CREATE TABLE goal_account (
    goal_id    BIGINT NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    PRIMARY KEY (goal_id, account_id)
);

CREATE INDEX idx_goal_account_goal ON goal_account(goal_id);
CREATE INDEX idx_goal_account_account ON goal_account(account_id);
