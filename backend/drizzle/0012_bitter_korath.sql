CREATE TABLE `mutation_receipts` (
	`key` text PRIMARY KEY NOT NULL,
	`fingerprint` text NOT NULL,
	`status` integer NOT NULL,
	`body` text NOT NULL,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `upstream_cache` (
	`key` text PRIMARY KEY NOT NULL,
	`value` text NOT NULL,
	`expires_at` integer NOT NULL,
	`stale_until` integer NOT NULL
);
