create table os_shares (
    id uuid not null,
    name varchar(255) not null,
    display_name varchar(255),
    comment varchar(65536),
    owner_id varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_shares_name unique (name)
);

create table os_share_properties (
    share_id uuid not null,
    property_key varchar(255) not null,
    property_value varchar(1000),
    constraint uk_share_properties primary key (share_id, property_key),
    constraint fk_share_properties_share foreign key (share_id) references os_shares (id)
);

create table os_recipients (
    id uuid not null,
    name varchar(255) not null,
    comment varchar(65536),
    owner_id varchar(255) not null,
    authentication_type varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_recipients_name unique (name)
);

create table os_recipient_tokens (
    id uuid not null,
    recipient_id uuid not null,
    activation_code varchar(36),
    token_hash varchar(64),
    activated boolean not null,
    expires_at timestamp(6) with time zone not null,
    superseded_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_recipient_tokens_activation_code unique (activation_code),
    constraint uk_recipient_tokens_token_hash unique (token_hash),
    constraint fk_recipient_tokens_recipient
        foreign key (recipient_id) references os_recipients (id)
);

create table os_share_permissions (
    id uuid not null,
    share_id uuid not null,
    recipient_id uuid not null,
    privilege varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_share_permission unique (share_id, recipient_id, privilege),
    constraint fk_share_permissions_share foreign key (share_id) references os_shares (id),
    constraint fk_share_permissions_recipient
        foreign key (recipient_id) references os_recipients (id)
);

create table os_shared_data_objects (
    id uuid not null,
    share_id uuid not null,
    source_asset_id varchar(255),
    full_name varchar(512) not null,
    type varchar(255) not null,
    source_format varchar(255),
    storage_location varchar(2048),
    auxiliary_locations varchar(8192),
    shared_as_schema varchar(255) not null,
    shared_as_table varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_shared_objects_source unique (share_id, full_name),
    constraint uk_shared_objects_alias unique (share_id, shared_as_schema, shared_as_table),
    constraint fk_shared_objects_share foreign key (share_id) references os_shares (id)
);
