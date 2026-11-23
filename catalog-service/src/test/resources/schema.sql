drop all objects;
create table categories(id uuid primary key,name varchar(150),slug varchar(180));
create table brands(id uuid primary key,name varchar(150),slug varchar(180));
create table products(id uuid primary key,category_id uuid,brand_id uuid,name varchar(255),slug varchar(300),status varchar(20),description varchar(1000),attributes varchar(4000) default '{}',version bigint);
create table product_variants(id uuid primary key,product_id uuid not null,sku varchar(100),size_label varchar(100),color_label varchar(100),price numeric(14,2),stock_quantity integer,reserved_quantity integer,status varchar(20),version bigint,updated_at timestamp);
create table product_images(id uuid primary key,product_id uuid not null,secure_url varchar(1000),
    alt_text varchar(255),sort_order integer,is_primary boolean,public_id varchar(500),asset_version varchar(50),
    width integer,height integer,asset_format varchar(20),asset_bytes bigint,checksum varchar(64),
    source_kind varchar(50),license_code varchar(100),source_uri varchar(1000),source_group_code varchar(200));
create table inventory_reservations(id uuid primary key,order_ref uuid,idempotency_key varchar(200) unique,status varchar(20),expires_at timestamp,created_at timestamp default current_timestamp,updated_at timestamp default current_timestamp);
create table inventory_reservation_items(reservation_id uuid,variant_id uuid,quantity integer,primary key(reservation_id,variant_id));
