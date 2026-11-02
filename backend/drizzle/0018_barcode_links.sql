CREATE TABLE `barcode_links` (
	`gtin` text NOT NULL,
	`store` text NOT NULL,
	`product_id` text NOT NULL,
	`created_at` integer NOT NULL,
	PRIMARY KEY(`gtin`, `store`)
);
