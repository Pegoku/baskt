ALTER TABLE `basket_items` ADD `bought_at` integer;--> statement-breakpoint
ALTER TABLE `purchase_lines` ADD `item_id` text;--> statement-breakpoint
ALTER TABLE `purchase_lines` ADD `barcode` text;--> statement-breakpoint
CREATE INDEX `purchase_lines_item_idx` ON `purchase_lines` (`item_id`);