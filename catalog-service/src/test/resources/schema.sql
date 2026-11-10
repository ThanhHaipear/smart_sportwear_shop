drop all objects;
create table products(id uuid primary key,name varchar(255),slug varchar(300),status varchar(20),description varchar(1000),version bigint);
create table product_variants(id uuid primary key,product_id uuid not null,sku varchar(100),size_label varchar(100),color_label varchar(100),price numeric(14,2),stock_quantity integer,reserved_quantity integer,status varchar(20),version bigint,updated_at timestamp);
create table inventory_reservations(id uuid primary key,order_ref uuid,idempotency_key varchar(200) unique,status varchar(20),expires_at timestamp,created_at timestamp default current_timestamp,updated_at timestamp default current_timestamp);
create table inventory_reservation_items(reservation_id uuid,variant_id uuid,quantity integer,primary key(reservation_id,variant_id));
