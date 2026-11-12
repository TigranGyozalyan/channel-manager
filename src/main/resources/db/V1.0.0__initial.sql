create table listings (
    id int8 primary key,
    name text
);

create table users (
    id int8 primary key,
    name text,
    firstname text,
    lastname text,
    email text
);

create table user_listings (
    user int8 references users(id),
    listing int8 references listings(id)
);