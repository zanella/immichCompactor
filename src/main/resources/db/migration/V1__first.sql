--
-- Initial schema for immich_compactor (SQLite)
--

CREATE TABLE user_info (
    id                  integer primary key autoincrement,
    api_key             varchar(255) not null,
    name                varchar(255) not null,
    immich_server_url   varchar(255) not null
);

--

CREATE TABLE assets_staging_area (
    user_id         integer not null,
    asset_id        varchar(255) not null primary key,
    content_type    varchar(255) not null,
    current_state   varchar(255) not null,

    foreign key (user_id) references user_info(id) on delete cascade
);

--

CREATE TABLE converted_assets (
    user_id         integer not null,
    asset_id        varchar(255) not null primary key,
    current_state   varchar(255) not null,

    foreign key (user_id) references user_info(id) on delete cascade
);

-- EOF
