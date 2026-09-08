CREATE TABLE `user_recipes` (
	`id` text PRIMARY KEY NOT NULL,
	`title` text NOT NULL,
	`description` text,
	`servings` text,
	`ingredient_lines` text NOT NULL,
	`steps` text NOT NULL,
	`image_url` text,
	`source_url` text,
	`origin` text DEFAULT 'manual' NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
