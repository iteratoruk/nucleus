create table "account"
(
  "id"                     bigserial    not null,
  "version"                bigint       not null,
  "created_by"             varchar(255),
  "created_date"           timestamptz,
  "account_identifier"     uuid         not null,
  "stakeholder_identifier" varchar(255) not null,
  "classification_code"    varchar(255) not null,
  "ledger_side"            varchar(255) not null,
  "status"                 varchar(255) not null,
  constraint "pk_account" primary key ("id"),
  constraint "uq_account_account_identifier" unique ("account_identifier")
);