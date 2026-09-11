ALTER TABLE users ADD COLUMN locale TEXT NOT NULL DEFAULT 'en'
    CHECK (locale IN ('en', 'ru'));
