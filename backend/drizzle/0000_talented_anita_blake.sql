CREATE TABLE `ai_cache` (
	`kind` text NOT NULL,
	`key` text NOT NULL,
	`response_json` text NOT NULL,
	`created_at` integer NOT NULL,
	PRIMARY KEY(`kind`, `key`)
);
--> statement-breakpoint
CREATE TABLE `basket_items` (
	`id` text PRIMARY KEY NOT NULL,
	`text` text NOT NULL,
	`quantity` integer DEFAULT 1 NOT NULL,
	`checked` integer DEFAULT false NOT NULL,
	`sort_order` integer DEFAULT 0 NOT NULL,
	`status` text DEFAULT 'NEW' NOT NULL,
	`error` text,
	`parsed_json` text,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `basket_matches` (
	`id` text PRIMARY KEY NOT NULL,
	`item_id` text NOT NULL,
	`store` text NOT NULL,
	`candidate_ids` text NOT NULL,
	`equivalences` text NOT NULL,
	`window_start` integer DEFAULT 0 NOT NULL,
	`shown_count` integer DEFAULT 3 NOT NULL,
	`chosen_product_id` text,
	`status` text DEFAULT 'PENDING' NOT NULL,
	`chosen_by` text,
	`confidence` real,
	`reason` text,
	`updated_at` integer NOT NULL,
	FOREIGN KEY (`item_id`) REFERENCES `basket_items`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `basket_matches_item_store` ON `basket_matches` (`item_id`,`store`);--> statement-breakpoint
CREATE TABLE `choices` (
	`id` text PRIMARY KEY NOT NULL,
	`item_text` text NOT NULL,
	`canonical` text NOT NULL,
	`store` text NOT NULL,
	`chosen_product_id` text,
	`chosen_title` text,
	`rejected_titles` text NOT NULL,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `choices_canonical_idx` ON `choices` (`canonical`);--> statement-breakpoint
CREATE TABLE `price_history` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`product_id` text NOT NULL,
	`price_cents` integer NOT NULL,
	`is_deal` integer DEFAULT false NOT NULL,
	`captured_at` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `price_history_product_idx` ON `price_history` (`product_id`,`captured_at`);--> statement-breakpoint
CREATE TABLE `products` (
	`id` text PRIMARY KEY NOT NULL,
	`store` text NOT NULL,
	`source_id` text NOT NULL,
	`title` text NOT NULL,
	`brand` text,
	`quantity_text` text NOT NULL,
	`unit_amount` real,
	`unit` text,
	`price_cents` integer NOT NULL,
	`regular_price_cents` integer,
	`unit_price_cents` integer,
	`unit_price_unit` text,
	`deal_text` text,
	`is_deal` integer DEFAULT false NOT NULL,
	`image_url` text,
	`source_url` text,
	`category` text,
	`available` integer DEFAULT true NOT NULL,
	`fetched_at` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `products_store_idx` ON `products` (`store`);--> statement-breakpoint
CREATE INDEX `products_title_idx` ON `products` (`title`);--> statement-breakpoint
CREATE TABLE `search_cache` (
	`store` text NOT NULL,
	`normalized_query` text NOT NULL,
	`product_ids` text NOT NULL,
	`fetched_at` integer NOT NULL,
	`expires_at` integer NOT NULL,
	PRIMARY KEY(`store`, `normalized_query`)
);
--> statement-breakpoint
CREATE TABLE `settings` (
	`key` text PRIMARY KEY NOT NULL,
	`value_json` text NOT NULL
);
--> statement-breakpoint
CREATE TABLE `tombstones` (
	`collection` text NOT NULL,
	`entity_id` text NOT NULL,
	`deleted_at` integer NOT NULL,
	PRIMARY KEY(`collection`, `entity_id`)
);
