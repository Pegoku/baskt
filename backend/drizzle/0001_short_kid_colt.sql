ALTER TABLE `basket_items` ADD `kind` text DEFAULT 'item' NOT NULL;--> statement-breakpoint
ALTER TABLE `basket_items` ADD `parent_id` text REFERENCES basket_items(id) ON DELETE CASCADE;--> statement-breakpoint
ALTER TABLE `basket_items` ADD `recipe_json` text;