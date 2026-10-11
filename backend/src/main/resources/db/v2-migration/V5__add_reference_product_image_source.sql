set search_path = public;

-- REFERENCE is for traceable, non-commercial educational datasets. It records
-- neither shop ownership nor a commercial licence; source_uri and license_code
-- remain mandatory so every published image can be audited.
alter table product_images
    drop constraint chk_product_images_source_kind;

alter table product_images
    add constraint chk_product_images_source_kind check (
        source_kind in ('OWNED', 'LICENSED', 'SYNTHETIC', 'REFERENCE')
    );
