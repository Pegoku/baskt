CREATE TABLE `recipe_favourites` (
	`id` text PRIMARY KEY NOT NULL,
	`title` text NOT NULL,
	`url` text NOT NULL,
	`image_url` text,
	`created_at` integer NOT NULL
);
