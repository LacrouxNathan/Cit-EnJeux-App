CREATE TABLE game (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    type TEXT NOT NULL CHECK (type IN ('BASE_GAME','EXPANSION','STANDALONE_EXPANSION')),
    base_game_id UUID REFERENCES game(id),
    publisher TEXT, author TEXT, publication_year INTEGER CHECK (publication_year > 0),
    min_players INTEGER CHECK (min_players > 0), max_players INTEGER CHECK (max_players > 0),
    duration_minutes INTEGER CHECK (duration_minutes > 0), minimum_age INTEGER CHECK (minimum_age >= 0),
    description TEXT, categories TEXT, mechanics TEXT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    archive_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (min_players IS NULL OR max_players IS NULL OR min_players <= max_players),
    CHECK ((type = 'BASE_GAME' AND base_game_id IS NULL) OR (type <> 'BASE_GAME' AND base_game_id IS NOT NULL)),
    CHECK (base_game_id IS NULL OR base_game_id <> id),
    CHECK (NOT archived OR (archive_reason IS NOT NULL AND length(trim(archive_reason)) > 0))
);
CREATE INDEX game_base_index ON game(base_game_id);
CREATE TABLE library_location (
    id UUID PRIMARY KEY,
    parent_id UUID REFERENCES library_location(id),
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    archived BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE game_copy (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game(id),
    inventory_id TEXT NOT NULL CHECK (inventory_id ~ '^[A-Za-z0-9-]+$'),
    location_id UUID NOT NULL REFERENCES library_location(id),
    physical_condition TEXT NOT NULL CHECK (length(trim(physical_condition)) > 0),
    missing_pieces TEXT, comments TEXT,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    block_reason TEXT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    archive_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (NOT blocked OR (block_reason IS NOT NULL AND length(trim(block_reason)) > 0)),
    CHECK (NOT archived OR (archive_reason IS NOT NULL AND length(trim(archive_reason)) > 0))
);
CREATE UNIQUE INDEX game_copy_inventory_unique ON game_copy(lower(inventory_id));
CREATE INDEX game_copy_game_index ON game_copy(game_id);
CREATE TABLE inventory_identifier (
    normalized_id TEXT PRIMARY KEY,
    copy_id UUID NOT NULL REFERENCES game_copy(id),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE game_cover_revision (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game(id),
    content BYTEA NOT NULL,
    content_type TEXT NOT NULL CHECK (content_type IN ('image/png','image/jpeg')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE game_cover (
    game_id UUID PRIMARY KEY REFERENCES game(id),
    revision_id UUID NOT NULL REFERENCES game_cover_revision(id),
    content BYTEA NOT NULL,
    content_type TEXT NOT NULL CHECK (content_type IN ('image/png','image/jpeg'))
);
CREATE TABLE library_audit (
    id UUID PRIMARY KEY,
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action TEXT NOT NULL,
    changes JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX library_audit_entity_index ON library_audit(entity_type,entity_id,created_at);
