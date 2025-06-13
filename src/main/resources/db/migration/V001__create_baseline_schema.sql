create table "account_template"
(
  "id"                  bigserial                   not null,
  "version"             int8                        not null,
  "created_by"          varchar(255),
  "created_date"        timestamp(6) with time zone not null,
  "last_modified_by"    varchar(255),
  "last_modified_date"  timestamp(6) with time zone,
  "account_template_id" varchar(255)                not null,
  "display_name"        varchar(255),
  primary key ("id")
);

create unique index "account_template_id_idx" on "account_template" ("account_template_id");

create table "customer_tranche"
(
  "id"                  bigserial                   not null,
  "version"             int8                        not null,
  "created_by"          varchar(255),
  "created_date"        timestamp(6) with time zone not null,
  "last_modified_by"    varchar(255),
  "last_modified_date"  timestamp(6) with time zone,
  "customer_tranche_id" uuid                        not null,
  "display_name"        varchar(255),
  primary key ("id")
);

create table "account"
(
  "id"                    bigserial                   not null,
  "version"               int8                        not null,
  "created_by"            varchar(255),
  "created_date"          timestamp(6) with time zone not null,
  "last_modified_by"      varchar(255),
  "last_modified_date"    timestamp(6) with time zone,
  "account_id"            uuid                        not null,
  "customer_id"           varchar(255)                not null,
  "status"                varchar(255)                not null,
  "internal"              bool                        not null,
  "internal_account_role" varchar(255),
  "account_template_id"   bigint                      not null,
  "customer_tranche_id"   bigint,
  primary key ("id")
);

create unique index "account_id_idx" on "account" ("account_id");

create index "account_status_idx" on "account" ("status");

create index "account_customer_idx" on "account" ("customer_id");

create unique index "account_internal_account_idx" on "account" ("customer_id", "internal", "internal_account_role");

alter table "account"
  add constraint "account_template_fk"
    foreign key ("account_template_id")
      references "account_template";

alter table "account"
  add constraint "customer_tranche_fk"
    foreign key ("customer_tranche_id")
      references "customer_tranche";

create table "account_feature"
(
  "id"                 bigserial                   not null,
  "version"            int8                        not null,
  "created_by"         varchar(255),
  "created_date"       timestamp(6) with time zone not null,
  "last_modified_by"   varchar(255),
  "last_modified_date" timestamp(6) with time zone,
  "name"               varchar(64)                 not null,
  "config"             jsonb,
  primary key ("id")
);

create unique index "account_feature_name_idx" on "account_feature" ("name");

create table "account_account_feature"
(
  "accounts_id" bigint not null,
  "features_id" bigint not null,
  primary key (accounts_id, features_id)
);

alter table "account_account_feature"
  add constraint "account_fk"
    foreign key ("accounts_id")
      references "account"
      on delete cascade;

alter table "account_account_feature"
  add constraint "account_feature_fk"
    foreign key ("features_id")
      references "account_feature"
      on delete no action;

create table "parameter_definition"
(
  "id"                 bigserial                   not null,
  "version"            int8                        not null,
  "created_by"         varchar(255),
  "created_date"       timestamp(6) with time zone not null,
  "last_modified_by"   varchar(255),
  "last_modified_date" timestamp(6) with time zone,
  "name"               varchar(255)                not null,
  "display_name"       varchar(255),
  "description"        varchar(255),
  primary key ("id")
);

create unique index "parameter_definition_name_idx" on "parameter_definition" ("name");

create table "parameter_value"
(
  "id"                 bigserial                   not null,
  "version"            int8                        not null,
  "created_by"         varchar(255),
  "created_date"       timestamp(6) with time zone not null,
  "last_modified_by"   varchar(255),
  "last_modified_date" timestamp(6) with time zone,
  "definition_id"      bigint                      not null,
  "level"              varchar(255)                not null,
  "resource_id"        varchar(255),
  "value"              text                        not null,
  "effective_from"     timestamp with time zone    not null default current_timestamp,
  "effective_to"       timestamp with time zone,
  primary key ("id")
);

alter table "parameter_value"
  add constraint "parameter_definition_fk"
    foreign key ("definition_id")
      references "parameter_definition";

create index "parameter_value_idx" on "parameter_value" ("definition_id", "level", "resource_id", "effective_from", "effective_to");

create table "ledger_entry"
(
  "id"                bigserial                   not null,
  "version"           int8                        not null,
  "created_by"        varchar(255),
  "created_date"      timestamp(6) with time zone not null,
  "account_id"        bigint                      not null,
  "reversed_entry_id" bigint,
  "operation_id"      uuid                        not null,
  "address"           varchar(255)                not null,
  "asset"             varchar(255)                not null,
  "phase"             varchar(64)                 not null,
  "amount"            numeric(19, 7)              not null,
  "timestamp"         timestamp with time zone    not null default current_timestamp,
  primary key ("id")
);

alter table "ledger_entry"
  add constraint "account_fk"
    foreign key ("account_id")
      references "account";

alter table "ledger_entry"
  add constraint "reversed_entry_fk"
    foreign key ("reversed_entry_id")
      references "ledger_entry";

alter table "ledger_entry"
  add constraint "ledger_entry_amount_nonzero"
    check ("amount" <> 0);

create index "ledger_entry_operation_idx" on "ledger_entry" ("operation_id");

create index "ledger_entry_account_ts_idx" on "ledger_entry" ("account_id", "timestamp");

create index "ledger_entry_groupby_idx" on "ledger_entry" ("address", "asset", "phase");

create index "ledger_entry_account_address_asset_idx" on "ledger_entry" ("account_id", "address", "asset");
