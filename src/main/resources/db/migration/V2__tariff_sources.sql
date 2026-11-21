create table tariff_input (source_id varchar(180) primary key, digest varchar(64) not null, filename varchar(200) not null, bytes bytea not null);
create index tariff_input_digest on tariff_input(digest);
create table upstream_state (name varchar(100) primary key, data text not null);
