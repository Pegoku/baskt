CREATE TABLE `chat_messages` (
	`id` text PRIMARY KEY NOT NULL,
	`basket_id` text NOT NULL,
	`role` text NOT NULL,
	`content` text NOT NULL,
	`proposal_json` text,
	`recipes_json` text,
	`created_at` integer NOT NULL
);
--> statement-breakpoint
CREATE INDEX `chat_messages_basket_idx` ON `chat_messages` (`basket_id`,`created_at`);