create table "account_template"
(
  "id"                  bigserial    not null,
  "version"             int8         not null,
  "created_by"          varchar(255),
  "created_date"        timestamp(6) with time zone not null,
  "last_modified_by"    varchar(255),
  "last_modified_date"  timestamp(6) with time zone,
  "account_template_id" varchar(255) not null,
  "display_name"        varchar(255),
  primary key ("id")
);

create unique index "account_template_id_idx" on "account_template" ("account_template_id");

create table "customer_tranche"
(
  "id"                  bigserial    not null,
  "version"             int8         not null,
  "created_by"          varchar(255),
  "created_date"        timestamp(6) with time zone not null,
  "last_modified_by"    varchar(255),
  "last_modified_date"  timestamp(6) with time zone,
  "customer_tranche_id" varchar(36)  not null,
  "display_name"        varchar(255),
  primary key ("id")
);

create table "account"
(
  "id"                  bigserial    not null,
  "version"             int8         not null,
  "created_by"          varchar(255),
  "created_date"        timestamp(6) with time zone not null,
  "last_modified_by"    varchar(255),
  "last_modified_date"  timestamp(6) with time zone,
  "account_id"          varchar(36)  not null,
  "account_template_id" bigint       not null,
  "customer_tranche_id" bigint,
  primary key ("id")
);

create unique index "account_id_idx" on "account" ("account_id");

alter table "account"
  add constraint "account_template_fk"
    foreign key ("account_template_id")
      references "account_template";

alter table "account"
  add constraint "customer_tranche_fk"
    foreign key ("customer_tranche_id")
      references "customer_tranche";

create table "parameter_definition"
(
  "id"                 bigserial    not null,
  "version"            int8         not null,
  "created_by"         varchar(255),
  "created_date"       timestamp(6) with time zone not null,
  "last_modified_by"   varchar(255),
  "last_modified_date" timestamp(6) with time zone,
  "name"               varchar(255) not null,
  "display_name"       varchar(255),
  "description"        varchar(255),
  primary key ("id")
);

create unique index "parameter_definition_name_idx" on "parameter_definition" ("name");

create table "parameter_value"
(
  "id"                      bigserial    not null,
  "version"                 int8         not null,
  "created_by"              varchar(255),
  "created_date"            timestamp(6) with time zone not null,
  "last_modified_by"        varchar(255),
  "last_modified_date"      timestamp(6) with time zone,
  "definition_id" bigint       not null,
  "level"                   varchar(255) not null,
  "resource_id"             varchar(255),
  "value"                   jsonb        not null,
  "effective_from"          timestamp with time zone not null default current_timestamp,
  "effective_to"            timestamp with time zone,
  primary key ("id")
);

alter table "parameter_value"
  add constraint "parameter_definition_fk"
    foreign key ("definition_id")
      references "parameter_definition";

create index "parameter_value_effective_date_idx" on "parameter_value" ("effective_from", "effective_to");

create index "parameter_value_level_idx" on "parameter_value" ("level");

create index "parameter_value_resource_id_idx" on "parameter_value" ("resource_id");
