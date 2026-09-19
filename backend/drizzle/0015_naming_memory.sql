CREATE TABLE `naming_memory` (
	`id` text PRIMARY KEY NOT NULL,
	`source` text NOT NULL,
	`keys` text NOT NULL,
	`preferred` text NOT NULL,
	`language` text NOT NULL,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `naming_memory_preferred_idx` ON `naming_memory` (`preferred`);