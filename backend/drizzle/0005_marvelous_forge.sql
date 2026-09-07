CREATE TABLE `purchase_lines` (
	`id` text PRIMARY KEY NOT NULL,
	`purchase_id` text NOT NULL,
	`name` text NOT NULL,
	`product_id` text,
	`quantity` real DEFAULT 1 NOT NULL,
	`unit_price_cents` integer,
	`total_price_cents` integer NOT NULL,
	`deal_text` text,
	`sort_order` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE INDEX `purchase_lines_purchase_idx` ON `purchase_lines` (`purchase_id`);--> statement-breakpoint
CREATE TABLE `purchases` (
	`id` text PRIMARY KEY NOT NULL,
	`store` text NOT NULL,
	`purchased_at` integer NOT NULL,
	`total_cents` integer NOT NULL,
	`source` text DEFAULT 'receipt' NOT NULL,
	`created_at` integer NOT NULL
);
