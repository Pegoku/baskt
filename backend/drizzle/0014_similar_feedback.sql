CREATE TABLE `similar_feedback` (
	`reference_product_id` text NOT NULL,
	`product_id` text NOT NULL,
	`up` integer NOT NULL,
	`created_at` integer NOT NULL,
	PRIMARY KEY(`reference_product_id`, `product_id`)
);
