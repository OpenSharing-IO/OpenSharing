create table os_shares (
    id binary(16) not null,
    name varchar(255) not null,
    display_name varchar(255),
    comment mediumtext,
    owner_id varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uk_shares_name unique (name)
) engine=InnoDB;

create table os_share_properties (
    share_id binary(16) not null,
    property_key varchar(255) not null,
    property_value varchar(1000),
    constraint uk_share_properties primary key (share_id, property_key),
    constraint fk_share_properties_share foreign key (share_id) references os_shares (id)
) engine=InnoDB;

create table os_recipients (
    id binary(16) not null,
    name varchar(255) not null,
    comment mediumtext,
    owner_id varchar(255) not null,
    authentication_type varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uk_recipients_name unique (name)
) engine=InnoDB;

create table os_recipient_tokens (
    id binary(16) not null,
    recipient_id binary(16) not null,
    activation_code varchar(36),
    token_hash varchar(64),
    activated bit not null,
    expires_at datetime(6) not null,
    superseded_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uk_recipient_tokens_activation_code unique (activation_code),
    constraint uk_recipient_tokens_token_hash unique (token_hash),
    constraint fk_recipient_tokens_recipient
        foreign key (recipient_id) references os_recipients (id)
) engine=InnoDB;

create table os_share_permissions (
    id binary(16) not null,
    share_id binary(16) not null,
    recipient_id binary(16) not null,
    privilege varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uk_share_permission unique (share_id, recipient_id, privilege),
    constraint fk_share_permissions_share foreign key (share_id) references os_shares (id),
    constraint fk_share_permissions_recipient
        foreign key (recipient_id) references os_recipients (id)
) engine=InnoDB;

create table os_shared_data_objects (
    id binary(16) not null,
    share_id binary(16) not null,
    source_asset_id varchar(255),
    full_name varchar(512) not null,
    type varchar(255) not null,
    source_format varchar(255),
    storage_location varchar(2048),
    auxiliary_locations varchar(8192),
    shared_as_schema varchar(255) not null,
    shared_as_table varchar(255) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (id),
    constraint uk_shared_objects_source unique (share_id, full_name),
    constraint uk_shared_objects_alias unique (share_id, shared_as_schema, shared_as_table),
    constraint fk_shared_objects_share foreign key (share_id) references os_shares (id)
) engine=InnoDB;
