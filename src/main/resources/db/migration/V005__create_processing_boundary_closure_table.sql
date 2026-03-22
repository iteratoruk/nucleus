create table "processing_boundary_closure"
(
  "id"                bigserial    not null,
  "version"           bigint       not null,
  "created_by"        varchar(255),
  "created_date"      timestamptz,
  "boundary"          varchar(255) not null,
  "closure_timestamp" timestamptz  not null,
  constraint "pk_processing_boundary_closure" primary key ("id")
);