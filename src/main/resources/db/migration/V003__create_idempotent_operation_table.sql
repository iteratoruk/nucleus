create table "idempotent_operation"
(
  "id"              bigserial    not null,
  "version"         bigint       not null,
  "created_by"      varchar(255),
  "created_date"    timestamptz,
  "operation_id"    varchar(100) not null,
  "idempotency_key" varchar(255) not null,
  "uri"             varchar(2048) not null,
  "response_body"   text         not null,
  constraint "pk_idempotent_operation" primary key ("id"),
  constraint "uq_idempotent_operation_key" unique ("operation_id", "idempotency_key")
);