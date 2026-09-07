CREATE TABLE `stock` (
	`id` text PRIMARY KEY NOT NULL,
	`text` text NOT NULL,
	`canonical` text NOT NULL,
	`quantity_text` text,
	`added_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
