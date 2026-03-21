create table "parameter_node"
(
  "id"                  bigserial    not null,
  "version"             bigint       not null,
  "created_by"          varchar(255),
  "created_date"        timestamptz,
  "classification_code" varchar(255) not null,
  "ledger_side"         varchar(255) not null,
  constraint "pk_parameter_node" primary key ("id"),
  constraint "uq_parameter_node_classification_code" unique ("classification_code")
);

create table "parameter_value"
(
  "id"                bigserial    not null,
  "version"           bigint       not null,
  "created_by"        varchar(255),
  "created_date"      timestamptz,
  "parameter_node_id" bigint       not null,
  "parameter_key"     varchar(255) not null,
  "value"             text         not null,
  "effective_datetime" timestamptz not null,
  constraint "pk_parameter_value" primary key ("id"),
  constraint "fk_parameter_value_node" foreign key ("parameter_node_id") references "parameter_node" ("id")
);

create index "idx_parameter_value_node_key_effective"
  on "parameter_value" ("parameter_node_id", "parameter_key", "effective_datetime");